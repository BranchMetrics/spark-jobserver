package spark.jobserver

import java.io.File

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import com.typesafe.config.{Config, ConfigFactory, ConfigValueFactory}
import org.slf4j.LoggerFactory
import spark.jobserver.CommonMessages.JobStarted
import spark.jobserver.io.{DataFileDAO, JobDAO, JobDAOActor}

import scala.collection.JavaConverters._
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Try

/**
  * The Spark Job Server is a web service that allows users to submit and run Spark jobs, check status,
  * and view results.
  * It may offer other goodies in the future.
  * It only takes in one optional command line arg, a config file to override the default (and you can still
  * use -Dsetting=value to override)
  * -- Configuration --
  * {{{
  *   spark {
  *     master = "local"
  *     jobserver {
  *       port = 8090
  *     }
  *   }
  * }}}
  */
object JobServer {
  val logger = LoggerFactory.getLogger(getClass)
   Thread.sleep(30000)
  // Allow custom function to create ActorSystem.  An example of why this is useful:
  // we can have something that stores the ActorSystem so it could be shut down easily later.
  def start(args: Array[String], makeSystem: Config => ActorSystem) {
    val defaultConfig = ConfigFactory.load()
    val config = if (args.length > 0) {
      val configFile = new File(args(0))
      if (!configFile.exists()) {
        println("Could not find configuration file " + configFile)
        sys.exit(1)
      }
      ConfigFactory.parseFile(configFile).withFallback(defaultConfig).resolve()
    } else {
      defaultConfig
    }
    logger.info("Starting JobServer with config {}",
                config.getConfig("spark").root.render())
    logger.info("Spray config: {}",
                config.getConfig("spray.can.server").root.render())
    val port = config.getInt("spark.jobserver.port")

    // TODO: Hardcode for now to get going. Make it configurable later.
    val system = makeSystem(config)
    val clazz = Class.forName(config.getString("spark.jobserver.jobdao"))
    val ctor =
      clazz.getDeclaredConstructor(Class.forName("com.typesafe.config.Config"))
    try {
      val jobDAO = ctor.newInstance(config).asInstanceOf[JobDAO]
      val daoActor =
        system.actorOf(Props(classOf[JobDAOActor], jobDAO), "dao-manager")
      val dataManager = system.actorOf(
        Props(classOf[DataManagerActor], new DataFileDAO(config)),
        "data-manager")
      val jarManager =
        system.actorOf(Props(classOf[JarManager], daoActor), "jar-manager")
      val contextPerJvm = config.getBoolean("spark.jobserver.context-per-jvm")
      val supervisor =
        system.actorOf(
          Props(if (contextPerJvm) {
            classOf[AkkaClusterSupervisorActor]
          } else { classOf[LocalContextSupervisorActor] }, daoActor),
          "context-supervisor")
      val jobInfo = system.actorOf(
        Props(classOf[JobInfoActor], jobDAO, supervisor),
        "job-info")

      // Add initial job JARs, if specified in configuration.
      storeInitialJars(config, jarManager)

      // Create initial contexts
      supervisor ! ContextSupervisor.AddContextsFromConfig

      new WebApi(system,
                 config,
                 port,
                 jarManager,
                 dataManager,
                 supervisor,
                 jobInfo).start()

      //create job from context
      startJobFromConfig(config, supervisor)

    } catch {
      case e: Exception =>
        e.printStackTrace()
        logger.error("Unable to start Spark JobServer: ", e)
        sys.exit(1)
    }

  }

  private def startJobFromConfig(config: Config, supervisor: ActorRef): Unit = {
    import ContextSupervisor._

    for (contexts <- Try(config.getObject("spark.contexts"))) {
      contexts.keySet().asScala.foreach { contextName  =>
        val streamContext=contextName + "-" + config.getString("spark.job.stream.topic")
        val startJobConfig = config.getConfig("spark.job")
        val jobConfig = startJobConfig.withFallback(config).resolve()
        logger.info("Pausing for 10 secs for context initialization....")
        Thread.sleep(10000)
        val msg = GetContext(streamContext)
        val value = (supervisor ? msg)(60 seconds)
        val jobManager: Option[ActorRef] =
          Await.result(value, 60 seconds) match {
            case (manager: ActorRef, resultActor: ActorRef) => Some(manager)
            case NoSuchContext                              => None
            case ContextInitError(err)                      => throw new RuntimeException(err)
          }
        jobManager.get.ask(
          JobManagerActor.StartJob(
            "stream",
            "io.branch.realtime.job.DruidStream",
            jobConfig,
            Set(classOf[JobStarted])))(Timeout(10 seconds))
      }
    }
  }
  private def storeInitialJars(config: Config, jarManager: ActorRef): Unit = {
    val initialJarPathsKey = "spark.jobserver.job-jar-paths"
    if (config.hasPath(initialJarPathsKey)) {
      val initialJarsConfig = config.getConfig(initialJarPathsKey).root

      logger.info("Adding initial job jars: {}", initialJarsConfig.render())

      val initialJars =
        initialJarsConfig.asScala.map {
          case (key, value) => (key, value.unwrapped.toString)
        }.toMap

      // Ensure that the jars exist
      for (jarPath <- initialJars.values) {
        val f = new java.io.File(jarPath)
        if (!f.exists) {
          val msg =
            if (f.isAbsolute) {
              s"Initial Jar File $jarPath does not exist"
            } else {
              s"Initial Jar File $jarPath (${f.getAbsolutePath}) does not exist"
            }

          throw new java.io.IOException(msg)
        }
      }

      val contextTimeout = util.SparkJobUtils.getContextTimeout(config)
      val future =
        (jarManager ? StoreLocalJars(initialJars))(contextTimeout.seconds)

      Await.result(future, contextTimeout.seconds) match {
        case InvalidJar => sys.error("Could not store initial job jars.")
        case _          =>
      }
    }
  }

  def main(args: Array[String]) {
    import scala.collection.JavaConverters._
    def makeSupervisorSystem(name: String)(config: Config): ActorSystem = {
      val configWithRole = config.withValue(
        "akka.cluster.roles",
        ConfigValueFactory.fromIterable(List("supervisor").asJava))
      ActorSystem(name, configWithRole)
    }
    start(args, makeSupervisorSystem("JobServer")(_))
  }

}
