package quickstart

import xitrum.Server
import quickstart.action.XitrumMetrics

object Boot {
  def main(args: Array[String]) {
    XitrumMetrics.start()
    Server.start()
  }
}
