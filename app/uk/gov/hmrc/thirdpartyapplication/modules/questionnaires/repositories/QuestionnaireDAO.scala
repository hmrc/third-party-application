/*
 * Copyright 2021 HM Revenue & Customs
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

package uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.repositories

import scala.collection.mutable
import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.domain.models._
import cats.implicits._
import scala.concurrent.Future
import scala.concurrent.ExecutionContext
import javax.inject.{Inject, Singleton}
import scala.collection.immutable.ListSet
import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.domain.services.DeriveContext

@Singleton
class QuestionnaireDAO @Inject()(implicit ec: ExecutionContext) {
  private val store: mutable.Map[QuestionnaireId, Questionnaire] = mutable.Map()

  import QuestionnaireDAO.Questionnaires._
  
  allIndividualQuestionnaires.map(q => store.put(q.id, q))
  
  // N.B. Using futures even though not necessary as it mixes better AND means any move to an actual Mongo collection is proof against lots of change

  def fetch(id: QuestionnaireId): Future[Option[Questionnaire]] = store.get(id).pure[Future]

  def fetchAll(): Future[List[Questionnaire]] = store.values.toList.pure[Future]

  def fetchActiveQuestionnaireGroupings() : Future[List[QuestionnaireGrouping]] = activeQuestionnaireGroupings.pure[Future]
}

object QuestionnaireDAO {
  object Questionnaires {
    object DevelopmentPractices {
      val question1 = YesNoQuestion(
        QuestionId("653d2ee4-09cf-46a0-bc73-350a385ae860"),
        Wording("Do your development practices follow our guidance?"),
        Statement(
          CompoundFragment(
            StatementText("You must develop software following our"),
            StatementLink("development practices (opens in a new tab)", "http://www.google.com"),
            StatementText(".")
          )
        )
      )

      val question2 = YesNoQuestion(
        QuestionId("6139f57d-36ab-4338-85b3-a2079d6cf376"),
        Wording("Does your error handling meet our specification?"),
        Statement(
          CompoundFragment(
            StatementText("We will check for evidence that you comply with our"),
            StatementLink("error handling specification (opens in new tab)", "http://www.google.com"),
            StatementText(".")
          )
        )
      )
      
      val question3 = YesNoQuestion(
        QuestionId("3c5cd29d-bec2-463f-8593-cd5412fab1e5"),
        Wording("Does your software meet accessibility standards?"),
        Statement(
          CompoundFragment(
            StatementText("Web-based software must meet level AA of the"),
            StatementLink("Web Content Accessibility Guidelines (WCAG) (opens in new tab)", "http://www.google.com"),
            StatementText(". Desktop software should follow equivalent offline standards.")
          )
        )
      )

      val questionnaire = Questionnaire(
        id = QuestionnaireId("796336a5-f7b4-4dad-8003-a818e342cbb4"),
        label = Label("Development practices"),
        questions = List(
          QuestionItem(question1), 
          QuestionItem(question2), 
          QuestionItem(question3)
        )
      )
    }
    
    object ServiceManagementPractices {
      val question1 = YesNoQuestion(
        QuestionId("b30e3d75-b16b-4bcb-b1ae-4f47d8b23fd0"),
        Wording("Do you have a process for notifying HMRC in the case of a security breach?"),
        Statement(
          StatementText("Any issues concerning the security of customer data must be reported immediately to HMRC."),
          CompoundFragment(
            StatementText("You must also "),
            StatementLink("notify the ICO about personal data breaches (opens in a new tab)", "http://www.google.com"),
            StatementText("within 72 hours of becoming aware of it.")
          )
        )
      )

      val question2 = YesNoQuestion(
        QuestionId("f67c64be-6a1a-41f4-a899-6c93fa7bd98d"),
        Wording("Do you provide a way for your customers or third parties to tell you about a security risk or incident?"),
        Statement(
          StatementText("We expect you to provide an easy contact method in the case of a security breach.")
        )
      )

      val questionnaire = Questionnaire(
        id = QuestionnaireId("ba30fb9b-db99-4d18-8ed8-8e5d94842bcc"),
        label = Label("Service management practices"),
        questions = List(
          QuestionItem(question1),
          QuestionItem(question2)
        )
      )
    }

    object HandlingPersonalData {
      val question1 = YesNoQuestion(
        QuestionId("31b9f463-eafe-4273-be80-227922048046"),
        Wording("Do you comply with the General Data Protection Regulation (GDPR)?"),
        Statement(
          StatementText("To be GDPR compliant you must keep customer data safe. This includes telling customers:"),
          StatementBullets(
            StatementText("what personal data you will be processing and why"),
            StatementText("that you are responsible for protecting their data"),
            CompoundFragment(
              StatementText("your "),
              StatementLink("lawful basis (opens in new tab) ", "http://www.google.com"),
              StatementText("for processing personal data")
            )
          )
        )
      )
            
      val question2 = YesNoQuestion(
        QuestionId("00ec1641-fc7f-4398-b537-348ddf7ec435"),
        Wording("Do you encrypt all customer data that you handle?"),
        Statement(
          CompoundFragment(
            StatementText("You must encrypt access tokens and personally identifiable data when it is stored and in transit. Read the "),
            StatementLink("GDPR guidelines on encryption (opens in new tab)", "http://www.google.com"),
            StatementText(".")
          )
        )
      )

      val question3 = ChooseOneOfQuestion(
        QuestionId("36c22dc2-8101-4469-adf4-12717ade4528"),
        Wording("Do you ensure that each customer's data cannot be accessed by other users?"),
        Statement(
          CompoundFragment(
            StatementText("Read the National Cyber Security Centre's guidance on "),
            StatementLink("keeping user data separate (opens in new tab)", "http://www.google.com"),
            StatementText("and best practice for "),
            StatementLink("a username and password security (opens in new tab)", "http://www.google.com"),
            StatementText(".")
          )
        ),
        ListSet(
          QuestionChoice("Yes"),
          QuestionChoice("No"),
          QuestionChoice("We only have one customer")
        )
      )

      val question4 = ChooseOneOfQuestion(
        QuestionId("164ff4b1-aa49-484e-82cf-1981835b34cf"),
        Wording("Do you have access control for employees using customer data?"),
        Statement(
          CompoundFragment(
            StatementText("Using a personal security policy and Role Based Access Control (RBAC) will ensure that employees can only access data essential to their job role. Read the "),
            StatementLink("National Cyber Security Centre's guidance", "http://www.google.com"),
            StatementText(".")
          )
        ),
        ListSet(
          QuestionChoice("Yes"),
          QuestionChoice("No"),
          QuestionChoice("We only have one user")
        )
      )

      val question5 = YesNoQuestion(
        QuestionId("a66cd7b1-e8c1-4982-9ee8-727aa172aa9b"),
        Wording("Do you store your customers' Government Gateway credentials?"),
        Statement(
          StatementText("Implementing OAuth 2.0 means there is no need to store Government Gateway credentials.")
        )
      )

      val question6 = YesNoQuestion(
        QuestionId("10249171-e87a-498e-8239-a417af29e2ff"),
        Wording("Are your customers able to access their own data?"),
        Statement(
          StatementText("You must give customers access to their data if they request it.")
        )
      )

      val questionnaire = Questionnaire(
        id = QuestionnaireId("19a26563-6d6a-488a-a81b-66436ccd17b7"),
        label = Label("Handling personal data"),
        questions = List(
          QuestionItem(question1),
          QuestionItem(question2),
          QuestionItem(question3),
          QuestionItem(question4),
          QuestionItem(question5, AskWhenContext(DeriveContext.IN_HOUSE_SOFTWARE, "No")),
          QuestionItem(question6)
        )
      )
    }

    object GrantingAuthorityToHMRC {
      val question1 = MultiChoiceQuestion(
        QuestionId("2e0becc5-1277-40ac-8910-eda9257884fd"),
        Wording("What is the location of the servers that store your customer data?"),
        Statement(
          StatementText("Select all that apply.")
        ),
        ListSet(
          QuestionChoice("In the UK"),
          QuestionChoice("In the European Economic Area (EEA)"),
          QuestionChoice("Outside the European Economic Area (EEA)")
        )
      )

      val question2 = TextQuestion(
        QuestionId("050783f3-df8c-44fc-9246-45977ad5b287"),
        Wording("Confirm the name of your software"),
        Statement(
          List(
            StatementText("We show this name to users when they authorise your software to interact with HMRC"),
            CompoundFragment(
              StatementText("It must comply with our "),
              StatementLink("naming guidelines(opens in a new tab)", "http://www.google.com")
            )
          )
        )
      )

      val question3 = YesNoQuestion(
        QuestionId("d208bdd6-e503-420f-a945-5f3595e399e6"),
        Wording("Does your software have a privacy policy?"),
        Statement(
          List(
            StatementText("We'll show this link to users when you request access to their data. This should tell your users how you store their personal information according to the GDPR guidelines."),
            StatementText("You can change this at any time.")
          )
        )
      )
      
      val question4 = YesNoQuestion(
        QuestionId("0a6d6973-c49a-49c3-93ff-de58daa1b90c"),
        Wording("Does your software have terms and conditions?"),
        Statement(
          List(
            StatementText("We'll show this link to users when you request access to their data. We recommend you have this statement in your software."),
            StatementText("You can change this at any time.")
          )
        )
      )
      
      val questionnaire = Questionnaire(
        id = QuestionnaireId("3a7f3369-8e28-447c-bd47-efbabeb6d93f"),
        label = Label("Granting authority to HMRC"),
        questions = List(
          QuestionItem(question1, AskWhenContext(DeriveContext.IN_HOUSE_SOFTWARE, "No")),
          QuestionItem(question2),
          QuestionItem(question3),
          QuestionItem(question4)
        )
      )
    }

    object ApplicationSecurity {
      val question1 = ChooseOneOfQuestion(
        QuestionId("227b404a-ae8a-4a76-9a4b-70bc568109ac"),
        Wording("Do you provide software as a service (SaaS)?"),
        Statement(
          StatementText("SaaS is centrally hosted and is delivered on a subscription basis.")
        ),
        ListSet(
          QuestionChoice("Yes"),
          QuestionChoice("No, customers install and manage their software")
        )
      )

      val question2 = YesNoQuestion(
        QuestionId("d6f895de-962c-4dc4-8399-9b995ab5da45"),
        Wording("Has your application passed software penetration testing?"),
        Statement(
          List(
            CompoundFragment(
              StatementText("Use either penetration test tools or an independant third party supplier. For penetration methodologies read the "),
              StatementLink("Open Web Application Security Project (OWASP) guide (opens in a new tab)", "http://www.google.com"),
              StatementText(".")
            )
          )
        )
      )

      val question3 = YesNoQuestion(
        QuestionId("a26fe624-179c-4beb-b469-63f2dbe358a0"),
        Wording("Do you audit security controls to ensure you comply with data protection law?"),
        Statement(
          List(
            CompoundFragment(
              StatementText("Asses your compliance using the "),
              StatementLink("ICO information security checklist (opens in a new tab)", "http://www.google.com"),
              StatementText(".")
            )
          )
        )
      )

      val questionnaire = Questionnaire(
        id = QuestionnaireId("6c6b18cc-239f-443b-b63d-89393014ea64"),
        label = Label("Application security"),
        questions = List(
          QuestionItem(question1),
          QuestionItem(question2, AskWhenAnswer(question1, "Yes")),
          QuestionItem(question3, AskWhenAnswer(question1, "Yes"))
        )
      )
    } 

    object FraudPreventionHeaders {
      val question1 = YesNoQuestion(
        QuestionId("968076cb-6267-43fe-a193-d1b7a090c844"),
        Wording("Have you implemented fraud prevention headers?"),
        Statement(
          CompoundFragment(
            StatementText("You must implement headers in line with our "),
            StatementLink("fraud prevention specification (opens in a new tab)", "http://www.google.com"),
            StatementText(".")
          )
        )
      )

      val question2 = YesNoQuestion(
        QuestionId("b58f910f-3630-4b0f-9431-7727aed4c2a1"),
        Wording("Do your fraud prevention headers meet our specification?"),
        Statement(
          CompoundFragment(
            StatementText("Check your headers meet the specification using the "),
            StatementLink("Test Fraud Prevention Headers API (opens in a new tab)", "http://www.google.com"),
            StatementText(".")
          )        
        )
      )

      val questionnaire = Questionnaire(
        id = QuestionnaireId("f6483de4-7bfa-49d2-b4a2-70f95316472e"),
        label = Label("Fraud prevention headers"),
        questions = List(
          QuestionItem(question1, AskWhenContext(DeriveContext.VAT_OR_ITSA, "True")),
          QuestionItem(question2, AskWhenContext(DeriveContext.VAT_OR_ITSA, "True"))
        )
      )
    }

    object MarketingYourSoftware {
      val question1 = YesNoQuestion(
        QuestionId("169f2ba5-4a07-438a-8eaa-cfc0efd5cdcf"),
        Wording("Do you use HMRC logos in your software, marketing or website?"),
        Statement(
          StatementText("You must not use the HMRC logo in any way.")
        )
      )

      val question2 = ChooseOneOfQuestion(
        QuestionId("3a37889c-6e6c-4aa8-a818-12ac28f7dcc2"),
        Wording("Do adverts in your software comply with UK standards?"),
        Statement(
          StatementText("Advertising that appears in your software must follow:"),
          StatementBullets(
            StatementLink("Advertising Standards Authority Codes (opens in a new tab)", "http://google.com"),
            StatementLink("UK marketing and advertising laws (opens in a new tab)", "http://google.com")
          )
        ),
        ListSet(
          QuestionChoice("Yes"),
          QuestionChoice("No"),
          QuestionChoice("There are no adverts in my software")
        )
      )
    
      val question3 = ChooseOneOfQuestion(
        QuestionId("0b4695a0-f9bd-4595-9383-279f64ff3e2e"),
        Wording("Do adverts in your software as 'HMRC recognised'?"),
        Statement(
          StatementText("Only use 'HMRC recognised' when advertising your software.  Do not use terms like 'accredited' or 'approved'.")
        ),
        ListSet(
          QuestionChoice("Yes"),
          QuestionChoice("No, I call it something else"),
          QuestionChoice("I do not advertise my software")
        )
      )

      val question4 = ChooseOneOfQuestion(
        QuestionId("1dd933ee-7f89-4eb4-a54e-bc54396afa55"),
        Wording("Do you get your customers' consent before sharing their personal data for marketing?"),
        Statement(
          CompoundFragment(
            StatementText("You must not share customers' personal data without their consent. Read the "),
            StatementLink("Direct Marketing Guidance (opens in a new tab) ", "http://google.com"),
            StatementText("from the Information Commissioner's Office.")
          )
        ),
        ListSet(
          QuestionChoice("Yes"),
          QuestionChoice("No"),
          QuestionChoice("I do not share customer data")
        )
      )
      
      
      val questionnaire = Questionnaire(
        id = QuestionnaireId("79590bd3-cc0d-49d9-a14d-6fa5dfc73f39"),
        label = Label("Marketing your software"),
        questions = List(
          QuestionItem(question1),
          QuestionItem(question2, AskWhenContext(DeriveContext.IN_HOUSE_SOFTWARE, "No")),
          QuestionItem(question3, AskWhenContext(DeriveContext.IN_HOUSE_SOFTWARE, "No")),
          QuestionItem(question4, AskWhenContext(DeriveContext.IN_HOUSE_SOFTWARE, "No"))
        )
      )
    }

    object BusinessDetails {
      val question1 = YesNoQuestion(
        QuestionId("62a12d00-e64a-4386-8418-dfb82e8ef676"),
        Wording("Do your development practices follow our guidance?"),
        Statement(
          CompoundFragment(
            StatementText("You must develop software following our"),
            StatementLink("development practices (opens in a new tab)", "http://www.google.com")
          )
        )
      )

      val questionnaire = Questionnaire(
        id = QuestionnaireId("ac69b129-524a-4d10-89a5-7bfa46ed95c7"),
        label = Label("Business details"),
        questions = List(
          QuestionItem(question1)
        )
      )
    }

    val allIndividualQuestionnaires = List(
      DevelopmentPractices.questionnaire,
      ServiceManagementPractices.questionnaire,
      HandlingPersonalData.questionnaire,
      GrantingAuthorityToHMRC.questionnaire,
      ApplicationSecurity.questionnaire,
      FraudPreventionHeaders.questionnaire,
      MarketingYourSoftware.questionnaire,
      BusinessDetails.questionnaire
    )

    val activeQuestionnaireGroupings = 
      List(
        QuestionnaireGrouping(
          heading = "Your processes",
          links = List(
            DevelopmentPractices.questionnaire.id,
            ServiceManagementPractices.questionnaire.id,
            HandlingPersonalData.questionnaire.id
          )            
        ),
        QuestionnaireGrouping(
          heading = "Your application",
          links = List(
            GrantingAuthorityToHMRC.questionnaire.id,
            ApplicationSecurity.questionnaire.id,
            FraudPreventionHeaders.questionnaire.id
          )
        ),
        QuestionnaireGrouping(
          heading = "Your marketing",
          links = List(
            MarketingYourSoftware.questionnaire.id
          )
        ),
        QuestionnaireGrouping(
          heading = "Your details",
          links = List(
            BusinessDetails.questionnaire.id
          )
        )
      )
  }
}