package uk.gov.hmrc.thirdpartyapplication.services

import javax.inject.{Inject, Singleton}
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.DispatchRequest
import uk.gov.hmrc.apiplatform.modules.common.services.{ApplicationLogger, EitherTHelper}

import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.{CommandFailures}
import uk.gov.hmrc.apiplatform.modules.common.domain.models.ApplicationId
import uk.gov.hmrc.http.HeaderCarrier
import scala.concurrent.ExecutionContext
import uk.gov.hmrc.thirdpartyapplication.services.commands.CommandHandler
import cats.data.NonEmptyList
import cats.data.EitherT
import scala.concurrent.Future
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.UnsubscribeFromApi

@Singleton
class ApplicationCommandService @Inject() (
  val applicationCommandDispatcher: ApplicationCommandDispatcher,
  val applicationCommandAuthenticator: ApplicationCommandAuthenticator
)(
  implicit ec: ExecutionContext
) extends ApplicationLogger {
  
  import CommandHandler._

  val E = EitherTHelper.make[Failures]
    //Failures      = NonEmptyList[CommandFailure]

    def authenticateAndDispatch(applicationId: ApplicationId, dispatchRequest: DispatchRequest)(implicit hc: HeaderCarrier): Future[EitherT[Future, Failures, Success]] = {

        // We need to break out isAuthorised so we check Stride if GKUser OR if process we check some other thing
    for {
      isAuthorised <- E.liftF(applicationCommandAuthenticator.authenticateCommand(dispatchRequest.command))
      dispatchResult <- applicationCommandDispatcher.dispatch(applicationId, dispatchRequest.command, dispatchRequest.verifiedCollaboratorsToNotify)
      result = E.cond(
        isAuthorised,
        dispatchResult,
        NonEmptyList.one(CommandFailures.GenericFailure("Not authenticated"))
      )
    } yield result
    

  }


}