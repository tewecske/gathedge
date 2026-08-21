package gathedge.backend.tools

import zio.*

import java.lang.management.ManagementFactory

import scala.jdk.CollectionConverters.*

/** Heap and GC visibility for a long-running batch job, logged on a fixed interval alongside whatever else the job
  * logs.
  */
object GcStats {

  /** Heap in use, and every collector's lifetime count and total time, for a run long enough that "is the GC keeping
    * up" is a real question.
    */
  def line: UIO[String] = {
    ZIO.succeed {
      val heap                = ManagementFactory.getMemoryMXBean.getHeapMemoryUsage
      val (collections, time) = {
        ManagementFactory.getGarbageCollectorMXBeans.asScala.foldLeft((0L, 0L)) { case ((count, millis), bean) =>
          (count + math.max(bean.getCollectionCount, 0L), millis + math.max(bean.getCollectionTime, 0L))
        }
      }
      f"heap ${heap.getUsed / (1024 * 1024)}MB / ${heap.getMax / (1024 * 1024)}MB, " +
        f"$collections%d GC(s), ${time}%d ms GC time"
    }
  }

  /** Logs [[line]] once every `interval` until interrupted. Fork this as a daemon around whatever it should watch, and
    * interrupt the fiber when that work finishes.
    */
  def logPeriodically(interval: Duration = 1.minute): URIO[Any, Nothing] = {
    (line.flatMap(text => ZIO.logInfo(s"GC stats: $text")) *> ZIO.sleep(interval)).forever
  }
}
