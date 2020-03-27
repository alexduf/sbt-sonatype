package xerial.sbt.sonatype

import java.io.{File, IOException}
import java.nio.charset.StandardCharsets
import java.util.Base64

import com.twitter.finagle.http.{MediaType, Request, Response}
import org.apache.http.auth.{AuthScope, UsernamePasswordCredentials}
import org.apache.http.impl.client.BasicCredentialsProvider
import org.sonatype.spice.zapper.ParametersBuilder
import org.sonatype.spice.zapper.client.hc4.Hc4ClientBuilder
import sbt.librarymanagement.ivy.{Credentials, DirectCredentials}
import wvlet.airframe.control.{ResultClass, Retry}
import wvlet.airframe.http.finagle.Finagle
import wvlet.airframe.http.{HttpClient, HttpStatus}
import wvlet.log.LogSupport
import xerial.sbt.sonatype.SonatypeException.{BUNDLE_ALREADY_EXISTS, STAGE_FAILURE, STAGE_IN_PROGRESS}

/**
  * REST API Client for Sonatype API (nexus-staigng)
  * https://repository.sonatype.org/nexus-staging-plugin/default/docs/rest.html
  */
class SonatypeClient(repositoryUrl: String,
                     cred: Seq[Credentials],
                     credentialHost: String,
                     timeoutMillis: Int = 30 * 60 * 1000)
    extends AutoCloseable
    with LogSupport {

  private lazy val directCredentials = {
    val credt: DirectCredentials = Credentials
      .forHost(cred, credentialHost)
      .getOrElse {
        throw new IllegalStateException(
          s"No credential is found for ${credentialHost}. Prepare ~/.sbt/(sbt_version)/sonatype.sbt file."
        )
      }
    credt
  }

  private val base64Credentials = {
    val credt = directCredentials
    Base64.getEncoder.encodeToString(s"${credt.userName}:${credt.passwd}".getBytes(StandardCharsets.UTF_8))
  }

  lazy val repoUri = {
    def repoBase(url: String) = if (url.endsWith("/")) url.dropRight(1) else url
    val url                   = repoBase(repositoryUrl)
    url
  }
  private val pathPrefix = {
    new java.net.URL(repoUri).getPath
  }

  private val httpClient = Finagle.client
    .withRetryContext {
      import wvlet.airframe.http.finagle._
      HttpClient
        .defaultHttpClientRetry[Request, Response]
        .withMaxRetry(15)
        .withJitter(initialIntervalMillis = 3000, maxIntervalMillis = 30000)
    }
    .withRequestFilter { request =>
      request.setContentTypeJson()
      request.accept = MediaType.Json
      request.authorization = s"Basic ${base64Credentials}"
      request
    }.newSyncClient(repoUri)

  override def close(): Unit = {
    httpClient.close()
  }

  import xerial.sbt.sonatype.SonatypeClient._

  def stagingRepositoryProfiles: Seq[StagingRepositoryProfile] = {
    info("Reading staging repository profiles...")
    val result =
      httpClient.get[Map[String, Seq[StagingRepositoryProfile]]](s"${pathPrefix}/staging/profile_repositories")
    result.getOrElse("data", Seq.empty)
  }

  def stagingRepository(repositoryId: String) = {
    info(s"Searching for repository ${repositoryId} ...")
    httpClient.get[String](s"${pathPrefix}/staging/repository/${repositoryId}")
  }

  def stagingProfiles: Seq[StagingProfile] = {
    info("Reading staging profiles...")
    val result = httpClient.get[StagingProfileResponse](s"${pathPrefix}/staging/profiles")
    result.data
  }

  def createStage(profile: StagingProfile, description: String): StagingRepositoryProfile = {
    info(s"Creating a staging repository in profile ${profile.name} with a description key: ${description}")
    val ret = httpClient.postOps[Map[String, String], CreateStageResponse](
      s"${pathPrefix}/staging/profiles/${profile.id}/start",
      Map("data" -> description)
    )
    // Retrieve created staging repository ids
    val repo = StagingRepositoryProfile(
      profile.id,
      profile.name,
      "open",
      repositoryId = ret.data.stagedRepositoryId,
      ret.data.description
    )
    info(s"Created successfully: ${repo.repositoryId}")
    repo
  }

  private val monitor = new ActivityMonitor()

  private val retryer =
    Retry.withBoundedBackoff(initialIntervalMillis = 1500, maxTotalWaitMillis = timeoutMillis)

  private def waitForStageCompletion(taskName: String,
                                     repo: StagingRepositoryProfile,
                                     terminationCond: StagingActivity => Boolean): StagingRepositoryProfile = {
    retryer
      .beforeRetry { ctx =>
        ctx.lastError match {
          case SonatypeException(STAGE_IN_PROGRESS, msg) =>
            info(msg)
          case _ =>
            warn(
              f"[${ctx.retryCount}/${ctx.maxRetry}] Execution failed: ${ctx.lastError.getMessage}. Retrying in ${ctx.nextWaitMillis / 1000.0}%.2f sec."
            )
        }
      }
      .withResultClassifier[Option[StagingActivity]] {
        case Some(activity) if terminationCond(activity) =>
          info(s"[${taskName}] Finished successfully")
          ResultClass.Succeeded
        case Some(activity) if (activity.containsError) =>
          error(s"[${taskName}] Failed")
          activity.reportFailure
          ResultClass.nonRetryableFailure(SonatypeException(STAGE_FAILURE, s"Failed to ${taskName} the repository"))
        case _ =>
          ResultClass.retryableFailure(
            SonatypeException(STAGE_IN_PROGRESS, s"Waiting for the completion of the ${taskName} process..."))
      }
      .run {
        val activities = activitiesOf(repo)
        monitor.report(activities)
        activities.lastOption
      }

    repo
  }

  def closeStage(currentProfile: StagingProfile, repo: StagingRepositoryProfile): StagingRepositoryProfile = {
    info(s"Closing staging repository $repo")
    val ret = httpClient.postOps[Map[String, StageTransitionRequest], Response](
      s"${pathPrefix}/staging/profiles/${repo.profileId}/finish",
      newStageTransitionRequest(currentProfile, repo)
    )
    waitForStageCompletion("close", repo, terminationCond = { _.isCloseSucceeded(repo.repositoryId) }).toClosed
  }

  def promoteStage(currentProfile: StagingProfile, repo: StagingRepositoryProfile): StagingRepositoryProfile = {
    info(s"Promoting staging repository $repo")
    val ret = httpClient.postOps[Map[String, StageTransitionRequest], Response](
      s"${pathPrefix}/staging/profiles/${repo.profileId}/promote",
      newStageTransitionRequest(currentProfile, repo)
    )
    if (ret.statusCode != HttpStatus.Created_201.code) {
      error(s"${ret.status}: ${ret.contentString}")
      throw new Exception("Failed to close the repository")
    }

    waitForStageCompletion("promote", repo, terminationCond = { _.isReleaseSucceeded(repo.repositoryId) })
  }

  def dropStage(currentProfile: StagingProfile, repo: StagingRepositoryProfile): Response = {
    info(s"Dropping staging repository $repo")
    httpClient.postOps[Map[String, StageTransitionRequest], Response](
      s"${pathPrefix}/staging/profiles/${repo.profileId}/drop",
      newStageTransitionRequest(currentProfile, repo)
    )
  }

  private def newStageTransitionRequest(currentProfile: StagingProfile,
                                        repo: StagingRepositoryProfile): Map[String, StageTransitionRequest] = {
    Map(
      "data" -> StageTransitionRequest(
        stagedRepositoryId = repo.repositoryId,
        targetRepositoryId = currentProfile.repositoryTargetId,
        description = repo.description
      )
    )
  }

  def activitiesOf(r: StagingRepositoryProfile): Seq[StagingActivity] = {
    debug(s"Checking activity logs of ${r.repositoryId} ...")
    httpClient.get[Seq[StagingActivity]](s"${pathPrefix}/staging/repository/${r.repositoryId}/activity")
  }

  def uploadBundle(localBundlePath: File, remoteUrl: String): Unit = {
    retryer
      .retryOn {
        case e: IOException if e.getMessage.contains("400 Bad Request") =>
          error(
            "Upload failed. Probably a previously uploaded bundle remains. Run sonatypeClean or sonatypeDropAll first.")
          Retry.nonRetryableFailure(SonatypeException(BUNDLE_ALREADY_EXISTS, e.getMessage))
      }
      .run {
        val parameters = ParametersBuilder.defaults().build()
        // Adding a trailing slash is necessary upload a bundle file to a proper location:
        val endpoint      = s"${remoteUrl}/"
        val clientBuilder = new Hc4ClientBuilder(parameters, endpoint)

        val credentialProvider = new BasicCredentialsProvider()
        val usernamePasswordCredentials =
          new UsernamePasswordCredentials(directCredentials.userName, directCredentials.passwd)

        credentialProvider.setCredentials(AuthScope.ANY, usernamePasswordCredentials)

        clientBuilder.withPreemptiveRealm(credentialProvider)

        import org.sonatype.spice.zapper.fs.DirectoryIOSource
        val deployables = new DirectoryIOSource(localBundlePath)

        val client = clientBuilder.build()
        try {
          info(s"Uploading bundle ${localBundlePath} to ${endpoint}")
          client.upload(deployables)
          info(s"Finished bundle upload: ${localBundlePath}")
        } finally {
          client.close()
        }
      }
  }

}

object SonatypeClient extends LogSupport {

  case class StagingProfileResponse(data: Seq[StagingProfile] = Seq.empty)

  /**
    * Staging profile is the information associated to a Sonatype account.
    */
  case class StagingProfile(id: String, name: String, repositoryTargetId: String)

  /**
    * Staging repository profile has an id of deployed artifact and the current staging state.
    */
  case class StagingRepositoryProfile(
      profileId: String,
      profileName: String,
      `type`: String,
      repositoryId: String,
      description: String
  ) {
    def stagingType: String = `type`

    override def toString =
      s"[$repositoryId] status:$stagingType, profile:$profileName($profileId) description: $description"
    def isOpen     = stagingType == "open"
    def isClosed   = stagingType == "closed"
    def isReleased = stagingType == "released"

    def toClosed   = copy(`type` = "closed")
    def toDropped  = copy(`type` = "dropped")
    def toReleased = copy(`type` = "released")

    def deployUrl: String = s"https://oss.sonatype.org/service/local/staging/deployByRepositoryId/${repositoryId}"
  }

  case class CreateStageResponse(
      data: StagingRepositoryRef
  )
  case class StagingRepositoryRef(
      stagedRepositoryId: String,
      description: String
  )

  case class StageTransitionRequest(
      stagedRepositoryId: String,
      targetRepositoryId: String,
      description: String
  )

  case class Prop(name: String, value: String)

  /**
    * ActivityEvent is an evaluation result (e.g., checksum, signature check, etc.) of a rule defined in a StagingActivity ruleset
    * @param timestamp
    * @param name
    * @param severity
    * @param properties
    */
  case class ActivityEvent(timestamp: String, name: String, severity: Int, properties: Seq[Prop]) {
    lazy val map         = properties.map(x => x.name -> x.value).toMap
    def ruleType: String = map.getOrElse("typeId", "other")
    def isFailure        = name == "ruleFailed"

    override def toString = {
      s"-event -- timestamp:$timestamp, name:$name, severity:$severity, ${properties.map(p => s"${p.name}:${p.value}").mkString(", ")}"
    }

    def showProgress(useErrorLog: Boolean = false): Unit = {
      val props = {
        val front =
          if (map.contains("typeId"))
            Seq(map("typeId"))
          else
            Seq.empty
        front ++ map.filter(_._1 != "typeId").map(p => s"${p._1}:${p._2}")
      }
      val messageLine = props.mkString(", ")
      val name_s      = name.replaceAll("rule(s)?", "")
      val message     = f"$name_s%10s: $messageLine"
      if (useErrorLog)
        logger.error(message)
      else
        logger.info(message)
    }
  }

  class ActivityMonitor {
    var reportedActivities = Set.empty[String]
    var reportedEvents     = Set.empty[ActivityEvent]

    def report(stagingActivities: Seq[StagingActivity]) = {
      for (sa <- stagingActivities) {
        if (!reportedActivities.contains(sa.started)) {
          logger.info(sa.activityLog)
          reportedActivities += sa.started
        }
        for (ae <- sa.events if !reportedEvents.contains(ae)) {
          ae.showProgress(useErrorLog = false)
          reportedEvents += ae
        }
      }
    }
  }

  /**
    * Staging activity is an action to the staged repository
    * @param name activity name, e.g. open, close, promote, etc.
    * @param started
    * @param stopped
    * @param events
    */
  case class StagingActivity(name: String, started: String, stopped: String, events: Seq[ActivityEvent]) {
    override def toString = {
      val b = Seq.newBuilder[String]
      b += activityLog
      for (e <- events)
        b += s" ${e.toString}"
      b.result.mkString("\n")
    }

    def activityLog = {
      val b = Seq.newBuilder[String]
      b += s"Activity name:${name}"
      b += s"started:${started}"
      if (stopped.nonEmpty) {
        b += s"stopped:${stopped}"
      }
      b.result().mkString(", ")
    }

    def showProgress: Unit = {
      logger.info(activityLog)
      val hasError = containsError
      for (e <- suppressEvaluateLog) {
        e.showProgress(hasError)
      }
    }

    def suppressEvaluateLog = {
      val in     = events.toIndexedSeq
      var cursor = 0
      val b      = Seq.newBuilder[ActivityEvent]
      while (cursor < in.size) {
        val current = in(cursor)
        if (cursor < in.size - 1) {
          val next = in(cursor + 1)
          if (current.name == "ruleEvaluate" && current.ruleType == next.ruleType) {
            // skip
          } else {
            b += current
          }
        }
        cursor += 1
      }
      b.result
    }

    def containsError = events.exists(_.severity != 0)

    def failureReport = suppressEvaluateLog.filter(_.isFailure)

    def reportFailure: Unit = {
      logger.error(activityLog)
      for (e <- failureReport) {
        e.showProgress(useErrorLog = true)
      }
    }

    def isReleaseSucceeded(repositoryId: String): Boolean = {
      events
        .find(_.name == "repositoryReleased")
        .exists(_.map.getOrElse("id", "") == repositoryId)
    }

    def isCloseSucceeded(repositoryId: String): Boolean = {
      events
        .find(_.name == "repositoryClosed")
        .exists(_.map.getOrElse("id", "") == repositoryId)
    }
  }
}
