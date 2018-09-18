/*
 * Copyright 2018 HM Revenue & Customs
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

package uk.gov.hmrc.services

import java.util.UUID
import javax.inject.{Inject, Singleton}

import play.api.Logger
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.bson.{BSONDocument, BSONObjectID}
import reactivemongo.play.json.ImplicitBSONHandlers._
import uk.gov.hmrc.connector.WSO2APIStoreConnector
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.models.MongoFormat._
import uk.gov.hmrc.models._
import uk.gov.hmrc.mongo.ReactiveRepository
import uk.gov.hmrc.mongo.json.ReactiveMongoFormats
import uk.gov.hmrc.repository.{ApplicationRepository, SubscriptionRepository}
import uk.gov.hmrc.util.mongo.IndexHelper._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@Singleton
class WSO2RestoreService @Inject()(wso2APIStoreConnector: WSO2APIStoreConnector,
                                   wso2APIStore: WSO2APIStore,
                                   subscriptionRepository: SubscriptionRepository,
                                   applicationRepository: ApplicationRepository,
                                   migrationRepository: WSO2RestoreRepository) {

  implicit val hc: uk.gov.hmrc.http.HeaderCarrier = HeaderCarrier()

  def restoreData(): Future[Seq[HasSucceeded]] = {
    Logger.info("Starting the WSO2 applications and subscriptions restore job")
    val eventualApplicationDatas = applicationRepository.fetchAll()
    val eventualAppsToMigrate = migrationRepository.fetchAllUnfinished()
    val a: Future[Seq[ApplicationData]] = for {
      appsToMigrate: Seq[WSO2RestoreData] <- eventualAppsToMigrate
      appDatas: Seq[ApplicationData] <- eventualApplicationDatas
      appToMigrate = appDatas.filter(app => appsToMigrate.map(_.appId).contains(app.id))
    } yield appToMigrate
    a.flatMap(_.foldLeft(Future.successful(Seq[HasSucceeded]()))((fs, appData) =>
      fs.flatMap((seq) => restoreApp(appData).map(_ ++ seq))
    ))
  }

  private def restoreApp(appData: ApplicationData): Future[Seq[HasSucceeded]] = {
    Logger.info(s"Starting to restore ${appData.name}.")
    wso2APIStore.createApplication(appData.wso2Username, appData.wso2Password, appData.wso2ApplicationName).flatMap(_ =>
      migrationRepository.save(WSO2RestoreData(appData.id, None, None, None, None, Some(false))).flatMap(
        _ => {
          val succeeded: Future[Seq[Future[HasSucceeded]]] = subscriptionRepository.getSubscriptions(appData.id).map(
            _.map(addSubscription(appData, _))
          )
          succeeded recover {
            case _ => Logger.info("Error dealing with subscriptions.")
          }

          Logger.info(s"Finished restoring application ${appData.name} and its subscriptions.")
          saveFinished(appData).flatMap(_ => succeeded.flatMap(a => Future.sequence(a)))
        }
      )
    )
  }

  private def saveFinished(appData: ApplicationData) = {
    migrationRepository.save(
      WSO2RestoreData(appData.id,
        Some(appData.wso2ApplicationName),
        Some(appData.tokens.production.clientId),
        Some(appData.tokens.production.wso2ClientSecret),
        Some(appData.tokens.production.accessToken),
        Some(true)
      )
    )
  }

  private def addSubscription(appData: ApplicationData, apiIdentifier: APIIdentifier) = {
    Logger.info(s"Trying to subscribe application ${appData.name} to API $apiIdentifier")
    wso2APIStore.addSubscription(appData.wso2Username,
      appData.wso2Password,
      appData.wso2ApplicationName,
      apiIdentifier,
      appData.rateLimitTier)
  }
}

case class WSO2RestoreData(appId: UUID,
                           wso2ApplicationName: Option[String],
                           clientId: Option[String],
                           wso2ClientSecret: Option[String],
                           accessToken: Option[String],
                           finished: Option[Boolean])

@Singleton
class WSO2RestoreRepository @Inject()(mongo: ReactiveMongoComponent)
  extends ReactiveRepository[WSO2RestoreData, BSONObjectID]("migration", mongo.mongoConnector.db,
    MongoFormat.formatWSO2RestoreData, ReactiveMongoFormats.objectIdFormats) {

  override def indexes = Seq(
    createSingleFieldAscendingIndex(
      indexFieldKey = "appId",
      indexName = Some("applicationIdIndex")
    ),
    createSingleFieldAscendingIndex(
      indexFieldKey = "finished",
      indexName = Some("finishedIndex")
    )
  )

  def save(migrationData: WSO2RestoreData) = {
    collection.find(BSONDocument("appId" -> migrationData.appId.toString)).one[BSONDocument].flatMap {
      case Some(document) => collection.update(selector = BSONDocument("_id" -> document.get("_id")), update = migrationData)
      case None => collection.insert(migrationData)
    }
  }

  def fetchAllUnfinished(): Future[Seq[WSO2RestoreData]] = {
    collection.find(BSONDocument("finished" -> false)).cursor[WSO2RestoreData]().collect[Seq]()
  }
}
