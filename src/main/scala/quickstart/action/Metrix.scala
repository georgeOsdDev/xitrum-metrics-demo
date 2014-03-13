package quickstart.action

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import java.util.concurrent.TimeUnit

import com.codahale.metrics.MetricRegistry
import com.codahale.metrics.json.MetricsModule
import com.fasterxml.jackson.databind.ObjectMapper
import nl.grons.metrics.scala.{InstrumentedBuilder, Timer}

import akka.actor.{Actor, ActorRef, Cancellable, ExtendedActorSystem, Props, Terminated}
import akka.cluster.{JmxMetricsCollector, NodeMetrics}
import akka.cluster.StandardMetrics.{Cpu, HeapMemory}
import glokka.Registry
import xitrum.{Action, Config, Log, SockJsAction, SockJsText}
import xitrum.annotation.{GET, Last, SOCKJS}
import xitrum.util.SeriDeseri

case object Subscribe
case object Start
case object Collect

object XitrumMetrics extends Log{

  // http://doc.akka.io/docs/akka/2.3.0/scala/cluster-usage.html
  val gossipInterval        = 3.seconds  // @TODO: Move to config
  val movingAverageHalfLife = 12.seconds // @TODO: Move to config

  // current system's address
  // http://grokbase.com/t/gg/akka-user/136m8mmyed/get-address-of-the-actorsystem-in-akka-2-2
  val provider       = Config.actorSystem.asInstanceOf[ExtendedActorSystem].provider
  val address        = provider.getDefaultAddress
  val jmx            = new JmxMetricsCollector(address, EWMA.alpha(movingAverageHalfLife, gossipInterval))

  // for metrics
  val metricRegistry = new MetricRegistry
  // for json writer
  // https://github.com/dropwizard/metrics/blob/master/metrics-json/src/test/java/com/codahale/metrics/json/MetricsModuleTest.java
  val module          = new MetricsModule(TimeUnit.SECONDS, TimeUnit.MILLISECONDS, true)
  val mapper          = new ObjectMapper().registerModule(module)
  def registryAsJson  = mapper.writeValueAsString(metricRegistry)


  // for glokka
  val actorRegistry  = Registry.start(Config.actorSystem, "proxy")

  // application constant
  val APIKEY         = "APIKEY" // @TODO: Move to config
  val PUBLISHER      = "XitrumMetricsPublisher"

  // jvm metrics collector
  val collector    = Config.actorSystem.actorOf(Props(new XitrumMetricsCollector()))
  val initialDelay = 1.seconds  // @TODO: Move to config
  val interval     = 30.seconds // @TODO: Move to config
  var cancellable: Cancellable = _


  def start() {
    // scheule jvm collecting
    // http://doc.akka.io/docs/akka/2.1.0/scala/scheduler.html
    // Use system's dispatcher as ExecutionContext
    import Config.actorSystem.dispatcher
    collector ! Start
    cancellable = Config.actorSystem.scheduler.schedule(initialDelay, interval, collector, Collect)
  }

  def stop() {
    cancellable.cancel()
  }


  // snippet from
  // http://doc.akka.io/docs/akka/2.3.0/scala/cluster-usage.html
  def logHeap(nodeMetrics: NodeMetrics) = nodeMetrics match {
    case HeapMemory(address, timestamp, used, committed, max) =>
      log.info(timestamp + "Used heap: {} MB", used.doubleValue / 1024 / 1024)
      log.info(timestamp + "Committed heap: {} MB", committed.doubleValue / 1024 / 1024)
      max match {
        case Some(v) => log.info(timestamp + "Max heap: {} MB", v / 1024 / 1024)
        case _       => // no heap max info
      }
    case _ => // no heap info
  }

  def logCpu(nodeMetrics: NodeMetrics) = nodeMetrics match {
    case Cpu(address, timestamp, Some(systemLoadAverage), cpuCombined, processors) =>
      log.info(timestamp + "Load: {} ({} processors)", systemLoadAverage, processors)
      log.info(timestamp + "cpuCombined: {} ", cpuCombined)
    case _ => // no cpu info
  }

  // The exponentially weighted moving average (EWMA)
  // Hard copy from
  // https://github.com/akka/akka/blob/master/akka-cluster/src/main/scala/akka/cluster/ClusterMetricsCollector.scala
  private object EWMA {
    /**
     * math.log(2)
     */
    private val LogOf2 = 0.69315

    /**
     * Calculate the alpha (decay factor) used in [[akka.cluster.EWMA]]
     * from specified half-life and interval between observations.
     * Half-life is the interval over which the weights decrease by a factor of two.
     * The relevance of each data sample is halved for every passing half-life duration,
     * i.e. after 4 times the half-life, a data sample’s relevance is reduced to 6% of
     * its original relevance. The initial relevance of a data sample is given by
     * 1 – 0.5 ^ (collect-interval / half-life).
     */
    def alpha(halfLife: FiniteDuration, collectInterval: FiniteDuration): Double = {
      val halfLifeMillis = halfLife.toMillis
      require(halfLife.toMillis > 0, "halfLife must be > 0 s")
      val decayRate = LogOf2 / halfLifeMillis
      1 - math.exp(-decayRate * collectInterval.toMillis)
    }
  }
}

trait metricsInstrument extends InstrumentedBuilder {
  val metricRegistry = XitrumMetrics.metricRegistry

}

// for publisher
trait registryLookUpper extends Log {
  this: Actor =>
  def lookUpPublisher {
    XitrumMetrics.actorRegistry ! Registry.LookupOrCreate(XitrumMetrics.PUBLISHER)
    context.become {
      case Registry.LookupResultOk(_, actorRef) =>
        doWithResult(actorRef)

      case Registry.LookupResultNone(_) =>
        val tmp = Config.actorSystem.actorOf(Props[XitrumMetricsPublisher])
        XitrumMetrics.actorRegistry ! Registry.RegisterByRef(XitrumMetrics.PUBLISHER, tmp)
        context.become {
          case Registry.RegisterResultOk(_, actorRef) =>
            doWithResult(actorRef)

          case Registry.RegisterResultConflict(_, actorRef) =>
            Config.actorSystem.stop(tmp)
            doWithResult(actorRef)
        }

      case unexpected =>
        log.warn("Unexpected message: " + unexpected)
    }
  }
  def doWithResult(actorRef:ActorRef):Unit
}

class XitrumMetricsCollector extends Actor
  with registryLookUpper
  with metricsInstrument {
  var publisher:ActorRef = _
  val timer: Timer = metrics.timer("get-jmx")

  def receive = {
    case Start =>
      log.debug("Start collector")
      lookUpPublisher
    case Collect =>
      log.debug("Publisher is not ready yet")
    case unexpected =>
      log.warn("Unexpected message: " + unexpected)
  }
  override def doWithResult(actorRef: ActorRef):Unit = {
    publisher = actorRef
    context.become {
      case Collect =>
        log.debug("Do jvm metrics sampling")
        timer.time {
          val nodeMetrics = XitrumMetrics.jmx.sample
          publisher ! nodeMetrics
        }
      case unexpected  =>
        log.warn("unexpected message" + unexpected)
    }
  }
}

class XitrumMetricsPublisher extends Actor
  with Log
  with metricsInstrument {
  private var clients = Seq[ActorRef]()

  metrics.gauge("cache-evictions") {
    clients.size
  }

  def receive = {
    case nodeMetrics: NodeMetrics =>
      log.debug("Received metrics from" + sender)
      clients.foreach(_ ! nodeMetrics)

    case Subscribe =>
      log.debug("Client joined" + sender)
      clients = clients :+ sender
      context.watch(sender)

    case Terminated(client) =>
      log.warn("Client terminated" , client)
      clients = clients.filterNot(_ == client)

    case unexpected =>
      log.warn("Unexpected message: " + unexpected)
  }
}

// write js receiver
trait XitrumMetrics extends Action {
  def js(namespace:String = null){
    jsAddToView(
      "(function (namespace){" +
      "  var ns = namespace || window;" +
      "  var initMetricsChannel = function(onMessageFunc, onCloseFunc) {" +
      "    var url = '" + sockJsUrl[XitrumMetricsChannel] + "';" +
      "    var socket;" +
      "    socket = new SockJS(url);" +
      "    socket.onopen = function(event) {" +
      "      socket.send('" + XitrumMetrics.APIKEY + "');" +
      "    };" +
      "    socket.onclose   = function(e){onCloseFunc(e);};" +
      "    socket.onmessage = function(e){onMessageFunc(e.data);}" +
      "  };" +
      " ns.initMetricsChannel = initMetricsChannel" +
     "})(" + namespace + ");"
    )
  }
}

// Default metrics viewer page
@Last
@GET("xitrum/metrics/viewer")
class DefaultXitrumMetrics extends XitrumMetrics
  with metricsInstrument
  with DefaultLayout {
  val requests = metricRegistry.meter(MetricRegistry.name(this.getClass.getName, "requests"));

  def execute(){
    requests.mark()
    js("window")
    respondView()
  }
}

@SOCKJS("xitrum/metrics")
class XitrumMetricsChannel extends SockJsAction with registryLookUpper {
  private var publisher: ActorRef = _
  def execute() {
    checkAPIkey
  }

  private def checkAPIkey {
    context.become {
      case SockJsText(text) if (text == XitrumMetrics.APIKEY) =>
        log.debug("Client joined with collect api key", sender)
        lookUpPublisher
      case SockJsText(key) =>
        respondSockJsText("wrong apikey")
      case ignore =>
        log.warn("Unexpected message" + ignore)
    }
  }

  override def doWithResult(actorRef: ActorRef) {
    log.warn("start subscribing")
    publisher = actorRef
    publisher ! Subscribe
    context.watch(publisher)
    context.become {
      case nodeMetrics: NodeMetrics =>
        log.debug("Metrics from publisher")
        nodeMetrics match {
          case HeapMemory(address, timestamp, used, committed, max) =>
            val msg = Map("TYPE"      -> "HEAP",
                          "ADRESS"    -> address,
                          "TIMESTAMP" -> timestamp,
                          "USED"      -> used,
                          "COMMITTED" -> committed,
                          "MAX"       -> max
                          )
            respondSockJsText(SeriDeseri.toJson(msg))
        }
        nodeMetrics match {
          case Cpu(address, timestamp, Some(systemLoadAverage), cpuCombined, processors) =>
            val msg = Map("TYPE"              -> "CPU",
                          "ADRESS"            -> address,
                          "TIMESTAMP"         -> timestamp,
                          "SYSTEMLOADAVERAGE" -> systemLoadAverage,
                          "CPUCOMBINED"       -> cpuCombined,
                          "PROCESSORS"        -> processors
                          )
            respondSockJsText(SeriDeseri.toJson(msg))
        }
        respondSockJsText(XitrumMetrics.registryAsJson)

      case Terminated(publisher) =>
        log.warn("publisher Tarminated")
        Thread.sleep(1000L * (scala.util.Random.nextInt(3)+1))
        lookUpPublisher

      case unexpected =>
        log.warn("Unexpected message: " + unexpected)
    }
  }
}
