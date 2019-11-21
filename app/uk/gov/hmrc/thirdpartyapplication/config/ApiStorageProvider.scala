/*
 * Copyright 2019 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.thirdpartyapplication.config

import javax.inject.{Inject, Provider, Singleton}
import play.api.inject.{Binding, Module}
import play.api.{Configuration, Environment}
import uk.gov.hmrc.thirdpartyapplication.services.{ApiGatewayStore, AwsApiGatewayStore, RealApiGatewayStore, StubApiGatewayStore}

class ApiStorageModule extends Module {

  override def bindings(environment: Environment, configuration: Configuration): Seq[Binding[_]] = {
    Seq(
      bind[ApiGatewayStore].toProvider[ApiStorageProvider]
    )
  }
}

@Singleton
class ApiStorageProvider @Inject()(config: ApiStorageConfig,
                                   stubApiGatewayStore: StubApiGatewayStore,
                                   realApiGatewayStore: RealApiGatewayStore,
                                   awsApiGatewayStore: AwsApiGatewayStore)
  extends Provider[ApiGatewayStore] {

  override def get() =  {
    if (config.skipWso2) {
      stubApiGatewayStore
    } else if (config.awsOnly) {
      awsApiGatewayStore
    } else {
      realApiGatewayStore
    }
  }
}

case class ApiStorageConfig(skipWso2: Boolean, awsOnly: Boolean)
