/*
 * Copyright 2022 HM Revenue & Customs
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
import scala.concurrent.Future.successful
import uk.gov.hmrc.play.http.HeaderCarrierConverter
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.apiplatform.modules.gkauth.domain.models.LoggedInRequest
import uk.gov.hmrc.apiplatform.modules.gkauth.domain.models.GatekeeperRoles
import uk.gov.hmrc.internalauth.client._
import scala.concurrent.ExecutionContext
import javax.inject.{Singleton, Inject}
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger

@Singleton
class LdapAuthorisationService @Inject() (auth: BackendAuthComponents)(implicit ec: ExecutionContext) extends ApplicationLogger {
  def refineLdap[A]: (Request[A]) => Future[Either[Request[A], LoggedInRequest[A]]] = (request) => {

    implicit val hc: HeaderCarrier = HeaderCarrierConverter.fromRequestAndSession(request, request.session)

    lazy val notAuthenticatedOrAuthorized: Either[Request[A], LoggedInRequest[A]] = Left(request)

    hc.authorization.fold({
      logger.debug("No Header Carrier Authoriation")
      successful(notAuthenticatedOrAuthorized)
    })(authorization => {
      auth.authConnector.authenticate(predicate = None, Retrieval.username ~ Retrieval.hasPredicate(LdapAuthorisationPredicate.gatekeeperReadPermission))
        .map {
          case (name ~ true) => Right(new LoggedInRequest(Some(name.value), GatekeeperRoles.READ_ONLY, request)) 
          case (name ~ false) => 
            logger.debug("No LDAP predicate matched")
            notAuthenticatedOrAuthorized
          case _ => 
            logger.debug("LDAP Authenticate failed to find user")
            notAuthenticatedOrAuthorized
        }
    })
  }
}
