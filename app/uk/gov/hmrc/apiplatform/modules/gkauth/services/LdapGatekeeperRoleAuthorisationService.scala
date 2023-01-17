/*
 * Copyright 2023 HM Revenue & Customs
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

package uk.gov.hmrc.apiplatform.modules.gkauth.services

import uk.gov.hmrc.internalauth.client.BackendAuthComponents
import play.api.mvc._
import scala.concurrent.Future
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.internalauth.client._
import scala.concurrent.ExecutionContext
import javax.inject.{Inject, Singleton}
import uk.gov.hmrc.thirdpartyapplication.config.AuthControlConfig

@Singleton
class LdapGatekeeperRoleAuthorisationService @Inject() (authControlConfig: AuthControlConfig, auth: BackendAuthComponents)(implicit ec: ExecutionContext)
    extends AbstractGatekeeperRoleAuthorisationService(authControlConfig) {

  protected def innerEnsureHasGatekeeperRole[A]()(implicit hc: HeaderCarrier): Future[Option[Result]] = {
    hc.authorization.fold[Future[Option[Result]]]({
      logger.debug("No Header Carrier Authorisation")
      UNAUTHORIZED_RESPONSE
    })(authorization => {
      auth.authConnector.authenticate(predicate = None, Retrieval.username ~ Retrieval.hasPredicate(LdapAuthorisationPredicate.gatekeeperReadPermission))
        .flatMap {
          case (name ~ true)  => OK_RESPONSE
          case (name ~ false) =>
            logger.debug("No LDAP predicate matched")
            UNAUTHORIZED_RESPONSE
          case _              =>
            logger.debug("LDAP Authenticate failed to find user")
            UNAUTHORIZED_RESPONSE
        }
    })
  }
}
