package uk.gov.hmrc.apiplatform.modules.approvals.utils

import com.kenshoo.play.metrics.Metrics
import org.scalatest.Suite
import play.api.Application

import scala.collection.JavaConverters

trait MetricsTestSupport {
  self: Suite =>

  def app: Application

  def givenCleanMetricRegistry(): Unit = {
    val registry = app.injector.instanceOf[Metrics].defaultRegistry
    for (
      metric <- JavaConverters
                  .asScalaIterator[String](registry.getMetrics.keySet().iterator())
    ) {
      registry.remove(metric)
    }
  }

}
