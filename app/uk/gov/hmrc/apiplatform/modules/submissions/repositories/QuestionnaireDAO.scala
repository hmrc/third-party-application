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

package uk.gov.hmrc.apiplatform.modules.submissions.repositories

import scala.collection.mutable
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models._
import cats.implicits._
import scala.concurrent.Future
import scala.concurrent.ExecutionContext
import javax.inject.{Inject, Singleton}
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.AskWhen.Context.Keys
import cats.data.NonEmptyList
import scala.collection.immutable.ListMap

@Singleton
class QuestionnaireDAO @Inject()(implicit ec: ExecutionContext) {
  private val store: mutable.Map[QuestionnaireId, Questionnaire] = mutable.Map()

  import QuestionnaireDAO.Questionnaires._
  
  allIndividualQuestionnaires.map(q => store.put(q.id, q))
  
  // N.B. Using futures even though not necessary as it mixes better AND means any move to an actual Mongo collection is proof against lots of change

  def fetch(id: QuestionnaireId): Future[Option[Questionnaire]] = store.get(id).pure[Future]

  def fetchActiveGroupsOfQuestionnaires() : Future[NonEmptyList[GroupOfQuestionnaires]] = activeQuestionnaireGroupings.pure[Future]
}

object QuestionnaireDAO {

  // *** Note - change this if the application name question changes. ***
  val questionIdsOfInterest = QuestionIdsOfInterest(
    applicationNameId             = Questionnaires.CustomersAuthorisingYourSoftware.question2.id,
    privacyPolicyUrlId            = Questionnaires.CustomersAuthorisingYourSoftware.question5.id,
    termsAndConditionsUrlId       = Questionnaires.CustomersAuthorisingYourSoftware.question7.id,
    organisationUrlId             = Questionnaires.OrganisationDetails.question1.id,
    responsibleIndividualNameId   = Questionnaires.OrganisationDetails.questionRI1.id,
    responsibleIndividualEmailId  = Questionnaires.OrganisationDetails.questionRI2.id,
    identifyYourOrganisationId    = Questionnaires.OrganisationDetails.question2.id
  )

  object Questionnaires {

    object OrganisationDetails {
      val questionRI1 = TextQuestion(
        QuestionId("36b7e670-83fc-4b31-8f85-4d3394908495"),
        Wording("What is the name of your responsible individual"),
        
        Statement(
          List(
            StatementText("The responsible individual:"),
            CompoundFragment(
              StatementText("ensures your software meets our "),
              StatementLink("terms of use", "/api-documentation/docs/terms-of-use")
            ),
            CompoundFragment(
              StatementText("understands the "),
              StatementLink("consequences of not meeting the terms of use", "/api-documentation/docs/terms-of-use")
            )
          )
        )
      )
      val questionRI2 = TextQuestion(
        QuestionId("fb9b8036-cc88-4f4e-ad84-c02caa4cebae"),
        Wording("What is the email address of your responsible individual"),
        Statement(
          List(
            StatementText("The responsible individual:"),
            CompoundFragment(
              StatementText("ensures your software meets our "),
              StatementLink("terms of use", "/api-documentation/docs/terms-of-use")
            ),
            CompoundFragment(
              StatementText("understands the "),
              StatementLink("consequences of not meeting the terms of use", "/api-documentation/docs/terms-of-use")
            )
          )
        )
      )

      val question1 = TextQuestion(
        QuestionId("b9dbf0a5-e72b-4c89-a735-26f0858ca6cc"),
        Wording("Give us your organisation's website URL"),
        Statement(
          List(
            StatementText("For example https://example.com")
          )
        ),
        Some(("My organisation doesn't have a website", Fail))
      )

      val question2 = ChooseOneOfQuestion(
        QuestionId("cbdf264f-be39-4638-92ff-6ecd2259c662"),
        Wording("Identify your organisation"),
        Statement(
          List(
            StatementText("Provide evidence that you or your organisation is officially registered in the UK. Choose one option.")
          )
        ),
        ListMap(
          (PossibleAnswer("Unique Taxpayer Reference (UTR)") -> Pass),
          (PossibleAnswer("VAT registration number") -> Pass),
          (PossibleAnswer("Corporation Tax Unique Taxpayer Reference (UTR)") -> Pass),
          (PossibleAnswer("PAYE reference") -> Pass),
          (PossibleAnswer("My organisation is in the UK and doesn't have any of these") -> Pass),
          (PossibleAnswer("My organisation is outside the UK and doesn't have any of these") -> Warn)
        )
      )

      val question2a = TextQuestion(
        QuestionId("4e148791-1a07-4f28-8fe4-ba3e18cdc118"),
        Wording("What is your company registration number?"),
        Statement(
          List(
            StatementText("You can find your company registration number on any official documentation you receive from Companies House."),
            StatementText("It's 8 characters long or 2 letters followed by 6  numbers. Check and documents from Companies House.")
          )
        ),
        Some(("My organisation doesn't have a company registration", Warn))
      )

      val question2b = TextQuestion(
        QuestionId("55da0b97-178c-45b5-a139-b61ad7b9ca84"),
        Wording("What is your Unique Taxpayer Reference (UTR)?"),
        Statement(List.empty)
      )
      val question2c = TextQuestion(
        QuestionId("dd12fd8b-907b-4ba1-95d3-ef6317f36199"),
        Wording("What is your VAT registration number?"),
        Statement(List.empty)
      )
      val question2d = TextQuestion(
        QuestionId("6be23951-ac69-47bf-aa56-86d3d690ee0b"),
        Wording("What is your Corporation Tax Unique Taxpayer Reference (UTR)?"),
        Statement(List.empty)
      )
      val question2e = TextQuestion(
        QuestionId("a143760e-72f3-423b-a6b4-558db37a3453"),
        Wording("What is your PAYE reference?"),
        Statement(List.empty)
      )
      
      val question3 = AcknowledgementOnly(
        QuestionId("a12f314e-bc12-4e0d-87ba-1326acb31008"),
        Wording("Provide evidence of your organisation's registration"),
        Statement(
          List(
            StatementText("You will need to provide evidence that your organisation is officially registered in a country outside of the UK."),
            StatementText("You will be asked for a digital copy of the official registration document.")
          )
        )
      )
      
      val questionnaire = Questionnaire(
        id = QuestionnaireId("ac69b129-524a-4d10-89a5-7bfa46ed95c7"),
        label = Label("Organisation details"),
        questions = NonEmptyList.of(
          QuestionItem(questionRI1),
          QuestionItem(questionRI2),
          QuestionItem(question1),
          QuestionItem(question2),
          QuestionItem(question2a, AskWhenAnswer(question2, "My organisation is in the UK and doesn't have any of these")),
          QuestionItem(question2b, AskWhenAnswer(question2, "Unique Taxpayer Reference (UTR)")),
          QuestionItem(question2c, AskWhenAnswer(question2, "VAT registration number")),
          QuestionItem(question2d, AskWhenAnswer(question2, "Corporation Tax Unique Taxpayer Reference (UTR)")),
          QuestionItem(question2e, AskWhenAnswer(question2, "PAYE reference")),
          QuestionItem(question3,  AskWhenAnswer(question2, "My organisation is outside the UK and doesn't have any of these"))
        )
      )
    }

    object DevelopmentPractices {
      val question1 = YesNoQuestion(
        QuestionId("653d2ee4-09cf-46a0-bc73-350a385ae860"),
        Wording("Do your development practices follow our guidance?"),
        Statement(
          CompoundFragment(
            StatementText("You must develop software following our"),
            StatementLink("development practices (opens in a new tab)", "https://developer.service.hmrc.gov.uk/api-documentation/docs/development-practices"),
            StatementText(".")
          )
        ),
        yesMarking = Pass,
        noMarking = Warn
      )

      val question2 = YesNoQuestion(
        id = QuestionId("6139f57d-36ab-4338-85b3-a2079d6cf376"),
        wording = Wording("Does your error handling meet our specification?"),
        statement = Statement(
          CompoundFragment(
            StatementText("We will check for evidence that you comply with our"),
            StatementLink("error handling specification (opens in new tab)", "https://developer.service.hmrc.gov.uk/api-documentation/docs/reference-guide#errors"),
            StatementText(".")
          )
        ),
        yesMarking = Pass,
        noMarking = Fail
      )
      
      val question3 = YesNoQuestion(
        QuestionId("3c5cd29d-bec2-463f-8593-cd5412fab1e5"),
        Wording("Does your software meet accessibility standards?"),
        Statement(
          CompoundFragment(
            StatementText("Web-based software must meet level AA of the"),
            StatementLink("Web Content Accessibility Guidelines (WCAG) (opens in new tab)", "https://www.w3.org/WAI/standards-guidelines/wcag/"),
            StatementText(". Desktop software should follow equivalent offline standards.")
          )
        ),
        yesMarking = Pass,
        noMarking = Warn
      )

      val questionnaire = Questionnaire(
        id = QuestionnaireId("796336a5-f7b4-4dad-8003-a818e342cbb4"),
        label = Label("Development practices"),
        questions = NonEmptyList.of(
          QuestionItem(question1), 
          QuestionItem(question2), 
          QuestionItem(question3)
        )
      )
    }
    
    object ServiceManagementPractices {
      val question1 = YesNoQuestion(
        QuestionId("f67c64be-6a1a-41f4-a899-6c93fa7bd98d"),
        Wording("Do you provide a way for your customers or third parties to tell you about a security risk or incident?"),
        Statement(
          StatementText("We expect you to provide an easy contact method in the case of a security breach.")
        ),
        yesMarking = Pass,
        noMarking = Fail
      )

      val question2 = YesNoQuestion(
        QuestionId("b30e3d75-b16b-4bcb-b1ae-4f47d8b23fd0"),
        Wording("Do you have a process for notifying HMRC in the case of a security breach?"),
        Statement(
          StatementText("Any issues concerning the security of customer data must be reported immediately to HMRC."),
          CompoundFragment(
            StatementText("You must also "),
            StatementLink("notify the ICO about personal data breaches (opens in a new tab)", "https://ico.org.uk/for-organisations/guide-to-data-protection/guide-to-the-general-data-protection-regulation-gdpr/personal-data-breaches"),
            StatementText("within 72 hours of becoming aware of it.")
          )
        ),
        yesMarking = Pass,
        noMarking = Fail
      )


      val questionnaire = Questionnaire(
        id = QuestionnaireId("ba30fb9b-db99-4d18-8ed8-8e5d94842bcc"),
        label = Label("Service management practices"),
        questions = NonEmptyList.of(
          QuestionItem(question1, AskWhenContext(Keys.IN_HOUSE_SOFTWARE, "No")),
          QuestionItem(question2)
        )
      )
    }

    object HandlingPersonalData {
      val question1 = YesNoQuestion(
        QuestionId("31b9f463-eafe-4273-be80-227922048046"),
        Wording("Do you comply with the UK General Data Protection Regulation (UK GDPR)?"),
        Statement(
          StatementText("To be UK GDPR compliant you must keep customer data safe. This includes telling customers:"),
          StatementBullets(
            StatementText("what personal data you will be processing and why"),
            StatementText("that you are responsible for protecting their data"),
            CompoundFragment(
              StatementText("your "),
              StatementLink("lawful basis (opens in new tab) ", "https://ico.org.uk/for-organisations/guide-to-data-protection/guide-to-the-general-data-protection-regulation-gdpr/lawful-basis-for-processing"),
              StatementText("for processing personal data")
            )
          )
        ),
        yesMarking = Pass,
        noMarking = Fail
      )
            
      val question2 = YesNoQuestion(
        QuestionId("00ec1641-fc7f-4398-b537-348ddf7ec435"),
        Wording("Do you encrypt all customer data that you handle?"),
        Statement(
          CompoundFragment(
            StatementText("You must encrypt access tokens and personally identifiable data when it is stored and in transit. Read the "),
            StatementLink("UK GDPR guidelines on encryption (opens in new tab)", "https://ico.org.uk/for-organisations/guide-to-data-protection/guide-to-the-general-data-protection-regulation-gdpr/encryption/encryption-and-data-transfer"),
            StatementText(".")
          )
        ),
        yesMarking = Pass,
        noMarking = Fail
      )

      val question3 = ChooseOneOfQuestion(
        QuestionId("36c22dc2-8101-4469-adf4-12717ade4528"),
        Wording("Do you ensure that each customer's data cannot be accessed by other users?"),
        Statement(
          CompoundFragment(
            StatementText("Read the National Cyber Security Centre's guidance on "),
            StatementLink("keeping user data separate (opens in new tab)", "https://www.ncsc.gov.uk/collection/cloud-security/implementing-the-cloud-security-principles/separation-between-users"),
            StatementText("and best practice for "),
            StatementLink("a username and password security (opens in new tab)", "https://www.ncsc.gov.uk/collection/passwords/updating-your-approach"),
            StatementText(".")
          )
        ),
        ListMap(
          (PossibleAnswer("Yes") -> Pass),
          (PossibleAnswer("No") -> Fail),
          (PossibleAnswer("We only have one customer") -> Pass)
        )
      )

      val question4 = ChooseOneOfQuestion(
        QuestionId("164ff4b1-aa49-484e-82cf-1981835b34cf"),
        Wording("Do you have access control for employees using customer data?"),
        Statement(
          CompoundFragment(
            StatementText("Using a personal security policy and Role Based Access Control (RBAC) will ensure that employees can only access data essential to their job role. Read the "),
            StatementLink("National Cyber Security Centre's guidance", "https://www.ncsc.gov.uk/collection/cloud-security/implementing-the-cloud-security-principles/personnel-security"),
            StatementText(".")
          )
        ),
        ListMap(
          (PossibleAnswer("Yes") -> Pass),
          (PossibleAnswer("No") -> Fail),
          (PossibleAnswer("We only have one user") -> Pass)
        )
      )

      val question5 = YesNoQuestion(
        QuestionId("10249171-e87a-498e-8239-a417af29e2ff"),
        Wording("Can customers access their data?"),
        Statement(
          CompoundFragment(
            StatementText("You must allow customers to change, export or delete their data if they want to. Read the "),
            StatementLink("UK GDPR guidelines on individuals rights", "https://ico.org.uk/for-organisations/guide-to-data-protection/guide-to-the-general-data-protection-regulation-gdpr/individual-rights/")
          )
        ),
        yesMarking = Pass,
        noMarking = Fail
      )

      val question6 = YesNoQuestion(
        QuestionId("a66cd7b1-e8c1-4982-9ee8-727aa172aa9b"),
        Wording("Do you store your customers' Government Gateway credentials?"),
        Statement(
          StatementText("Implementing OAuth 2.0 means there is no need to store Government Gateway credentials.")
        ),
        yesMarking = Fail,
        noMarking = Pass
      )

      val questionnaire = Questionnaire(
        id = QuestionnaireId("19a26563-6d6a-488a-a81b-66436ccd17b7"),
        label = Label("Handling personal data"),
        questions = NonEmptyList.of(
          QuestionItem(question1),
          QuestionItem(question2),
          QuestionItem(question3),
          QuestionItem(question4),
          QuestionItem(question5, AskWhenContext(Keys.IN_HOUSE_SOFTWARE, "No")),
          QuestionItem(question6, AskWhenContext(Keys.IN_HOUSE_SOFTWARE, "No"))
        )
      )
    }

    object CustomersAuthorisingYourSoftware {
      val question1 = AcknowledgementOnly(
        QuestionId("95da25e8-af3a-4e05-a621-4a5f4ca788f6"),
        Wording("Customers authorising your software"),
        Statement(
          List(
            StatementText("Your customers will see the information you provide here when they authorise your software to interact with HMRC."),
            StatementText("Before you continue, you will need:"),
            StatementBullets(
              List(
                StatementText("the name of your software"),
                StatementText("the location of your servers which store customer data"),
                StatementText("a link to your privacy policy"),
                StatementText("a link to your terms and conditions")
              )
            )
          )
        )
      )

      val question2 = TextQuestion(
        QuestionId("4d5a41c8-8727-4d09-96c0-e2ce1bc222d3"),
        Wording("Confirm the name of your software"),
        Statement(
          List(
            StatementText("We show this name to your users when they authorise your software to interact with HMRC."),
            CompoundFragment(
              StatementText("It must comply with our "),
              StatementLink("naming guidelines (opens in a new tab)", "https://developer.service.hmrc.gov.uk/api-documentation/docs/using-the-hub/name-guidelines"),
              StatementText(".")
            ),
            StatementText("Application name")
          )
        )
      )

      val question3 = MultiChoiceQuestion(
        QuestionId("57d706ad-c0b8-462b-a4f8-90e7aa58e57a"),
        Wording("Where are your servers that process customer information?"),
        Statement(
          StatementText("For cloud software, check the server location with your cloud provider."),
          CompoundFragment(
            StatementText("Learn about "),
            StatementLink("adequacy agreements (opens in a new tab)", "https://ico.org.uk/for-organisations/dp-at-the-end-of-the-transition-period/data-protection-and-the-eu-in-detail/adequacy/"),
            StatementText(" or "),
            StatementLink("check if a country has an adequacy agreement (opens in a new tab)", "https://ico.org.uk/for-organisations/dp-at-the-end-of-the-transition-period/data-protection-and-the-eu-in-detail/the-uk-gdpr/international-data-transfers/#:~:text=Andorra%2C%20Argentina%2C%20Canada%20(commercial,a%20finding%20of%20adequacy%20about"),
            StatementText(" with the UK.")
          ),
          StatementText("Select all that apply.")
        ),
        ListMap(
          (PossibleAnswer("In the UK") -> Pass),
          (PossibleAnswer("In the European Economic Area (EEA)") -> Pass),
          (PossibleAnswer("Outside the EEA with adequacy agreements") -> Pass),
          (PossibleAnswer("Outside the EEA with no adequacy agreements") -> Pass)
        )
      )

      val question4 = ChooseOneOfQuestion(
        QuestionId("b0ae9d71-e6a7-4cf6-abd4-7eb7ba992bc6"),
        Wording("Do you have a privacy policy URL for your software?"),
        Statement(
          List(
            StatementText("You need a privacy policy covering the software you request production credentials for.")
          )
        ),
        ListMap(
          (PossibleAnswer("Yes") -> Pass),
          (PossibleAnswer("No") -> Fail),
          (PossibleAnswer("The privacy policy is in desktop software") -> Pass)
        )
      )

      val question5 = TextQuestion(
        QuestionId("c0e4b068-23c9-4d51-a1fa-2513f50e428f"),
        Wording("What is your privacy policy URL?"),
        Statement(
          List(
            StatementText("For example https://example.com/privacy-policy")
          )
        )
      )

      val question6 = ChooseOneOfQuestion(
        QuestionId("ca6af382-4007-4228-a781-1446231578b9"),
        Wording("Do you have a terms and conditions URL for your software?"),
        Statement(
          List(
            StatementText("You need terms and conditions covering the software you request production credentials for.")
          )
        ),
        ListMap(
          (PossibleAnswer("Yes") -> Pass),
          (PossibleAnswer("No") -> Fail),
          (PossibleAnswer("The terms and conditions are in desktop software") -> Pass)
        )
      )

      val question7 = TextQuestion(
        QuestionId("0a6d6973-c49a-49c3-93ff-de58daa1b90c"),
        Wording("What is your terms and conditions URL?"),
        Statement(
          List(
            StatementText("For example https://example.com/terms-conditions")
          )
        )
      )
      
      val questionnaire = Questionnaire(
        id = QuestionnaireId("3a7f3369-8e28-447c-bd47-efbabeb6d93f"),
        label = Label("Customers authorising your software"),
        questions = NonEmptyList.of(
          QuestionItem(question1),
          QuestionItem(question2),
          QuestionItem(question3, AskWhenContext(Keys.IN_HOUSE_SOFTWARE, "No")),
          QuestionItem(question4),
          QuestionItem(question5, AskWhenAnswer(question4, "Yes")),
          QuestionItem(question6),
          QuestionItem(question7, AskWhenAnswer(question6, "Yes"))
        )
      )
    }

    object SoftwareSecurity {
      val question1 = ChooseOneOfQuestion(
        QuestionId("227b404a-ae8a-4a76-9a4b-70bc568109ac"),
        Wording("Do you provide software as a service (SaaS)?"),
        Statement(
          StatementText("SaaS is centrally hosted and is delivered on a subscription basis.")
        ),
        ListMap(
          (PossibleAnswer("Yes") -> Pass),
          (PossibleAnswer("No, customers install and manage their software") -> Pass)
        )
      )

      val question2 = YesNoQuestion(
        QuestionId("d6f895de-962c-4dc4-8399-9b995ab5da45"),
        Wording("Has your application passed software penetration testing?"),
        Statement(
          List(
            CompoundFragment(
              StatementText("Use either penetration test tools or an independant third party supplier. For penetration methodologies read the "),
              StatementLink("Open Web Application Security Project (OWASP) guide (opens in a new tab)", "https://wiki.owasp.org/index.php/Penetration_testing_methodologies"),
              StatementText(".")
            )
          )
        ),
        yesMarking = Pass,
        noMarking = Warn
      )

      val question3 = YesNoQuestion(
        QuestionId("a26fe624-179c-4beb-b469-63f2dbe358a0"),
        Wording("Do you audit security controls to ensure you comply with data protection law?"),
        Statement(
          List(
            CompoundFragment(
              StatementText("Assess your compliance using the "),
              StatementLink("ICO information security checklist (opens in a new tab)", "https://ico.org.uk/for-organisations/sme-web-hub/checklists/data-protection-self-assessment"),
              StatementText(".")
            )
          )
        ),
        yesMarking = Pass,
        noMarking = Warn
      )

      val questionnaire = Questionnaire(
        id = QuestionnaireId("6c6b18cc-239f-443b-b63d-89393014ea64"),
        label = Label("Software security"),
        questions = NonEmptyList.of(
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
            StatementLink("fraud prevention specification (opens in a new tab)", "https://developer.service.hmrc.gov.uk/guides/fraud-prevention"),
            StatementText(".")
          )
        ),
        yesMarking = Pass,
        noMarking = Pass
      )

      val question2 = YesNoQuestion(
        QuestionId("b58f910f-3630-4b0f-9431-7727aed4c2a1"),
        Wording("Do your fraud prevention headers meet our specification?"),
        Statement(
          CompoundFragment(
            StatementText("Check your headers meet the specification using the "),
            StatementLink("Test Fraud Prevention Headers API (opens in a new tab)", "https://developer.service.hmrc.gov.uk/api-documentation/docs/api/service/txm-fph-validator-api/1.0"),
            StatementText(".")
          )        
        ),
        yesMarking = Pass,
        noMarking = Fail
      )

      val questionnaire = Questionnaire(
        id = QuestionnaireId("f6483de4-7bfa-49d2-b4a2-70f95316472e"),
        label = Label("Fraud prevention headers"),
        questions = NonEmptyList.of(
          QuestionItem(question1, AskWhenContext(Keys.VAT_OR_ITSA, "Yes")),
          QuestionItem(question2, AskWhenContext(Keys.VAT_OR_ITSA, "Yes"))
        )
      )
    }

    object MarketingYourSoftware {
      val question1 = YesNoQuestion(
        QuestionId("169f2ba5-4a07-438a-8eaa-cfc0efd5cdcf"),
        Wording("Do you use HMRC logos in your software, marketing or website?"),
        Statement(
          StatementText("You must not use the HMRC logo in any way.")
        ),
        yesMarking = Warn,
        noMarking = Pass
      )

      val question2 = ChooseOneOfQuestion(
        QuestionId("3a37889c-6e6c-4aa8-a818-12ac28f7dcc2"),
        Wording("Do adverts in your software comply with UK standards?"),
        Statement(
          StatementText("Advertising that appears in your software must follow:"),
          StatementBullets(
            StatementLink("Advertising Standards Authority Codes (opens in a new tab)", "https://www.asa.org.uk/codes-and-rulings/advertising-codes.html "),
            StatementLink("UK marketing and advertising laws (opens in a new tab)", "https://www.gov.uk/marketing-advertising-law/regulations-that-affect-advertising ")
          )
        ),
        ListMap(
          (PossibleAnswer("Yes") -> Pass),
          (PossibleAnswer("No") -> Warn),
          (PossibleAnswer("There are no adverts in my software") -> Pass)
        )
      )
    
      val question3 = ChooseOneOfQuestion(
        QuestionId("0b4695a0-f9bd-4595-9383-279f64ff3e2e"),
        Wording("Do you advertise your software as 'HMRC recognised'?"),
        Statement(
          StatementText("Only use 'HMRC recognised' when advertising your software.  Do not use terms like 'accredited' or 'approved'.")
        ),
        ListMap(
          PossibleAnswer("Yes") -> Pass,
          PossibleAnswer("No, I call it something else") -> Warn,
          PossibleAnswer("I do not advertise my software") -> Pass
        )
      )

      val question4 = ChooseOneOfQuestion(
        QuestionId("1dd933ee-7f89-4eb4-a54e-bc54396afa55"),
        Wording("Do you get your customers' consent before sharing their personal data for marketing?"),
        Statement(
          CompoundFragment(
            StatementText("You must not share customers' personal data without their consent. Read the "),
            StatementLink("Direct Marketing Guidance (opens in a new tab) ", "https://ico.org.uk/for-organisations/guide-to-pecr/electronic-and-telephone-marketing/"),
            StatementText("from the Information Commissioner's Office.")
          )
        ),
        ListMap(
          (PossibleAnswer("Yes") -> Pass),
          (PossibleAnswer("No") -> Fail),
          (PossibleAnswer("I do not share customer data") -> Pass)
        )
      )
      
      
      val questionnaire = Questionnaire(
        id = QuestionnaireId("79590bd3-cc0d-49d9-a14d-6fa5dfc73f39"),
        label = Label("Marketing your software"),
        questions = NonEmptyList.of(
          QuestionItem(question1),
          QuestionItem(question2, AskWhenContext(Keys.IN_HOUSE_SOFTWARE, "No")),
          QuestionItem(question3, AskWhenContext(Keys.IN_HOUSE_SOFTWARE, "No")),
          QuestionItem(question4, AskWhenContext(Keys.IN_HOUSE_SOFTWARE, "No"))
        )
      )
    }

    val allIndividualQuestionnaires = List(
      OrganisationDetails.questionnaire,
      DevelopmentPractices.questionnaire,
      ServiceManagementPractices.questionnaire,
      HandlingPersonalData.questionnaire,
      CustomersAuthorisingYourSoftware.questionnaire,
      SoftwareSecurity.questionnaire,
      FraudPreventionHeaders.questionnaire,
      MarketingYourSoftware.questionnaire
    )

    val activeQuestionnaireGroupings = 
      NonEmptyList.of(
        GroupOfQuestionnaires(
          heading = "About your organisation",
          links = NonEmptyList.of(
            OrganisationDetails.questionnaire,
            MarketingYourSoftware.questionnaire
          )
        ),
        GroupOfQuestionnaires(
          heading = "About your processes",
          links = NonEmptyList.of(
            DevelopmentPractices.questionnaire,
            ServiceManagementPractices.questionnaire
          )            
        ),
        GroupOfQuestionnaires(
          heading = "About your software",
          links = NonEmptyList.of(
            HandlingPersonalData.questionnaire,
            CustomersAuthorisingYourSoftware.questionnaire,
            SoftwareSecurity.questionnaire,
            FraudPreventionHeaders.questionnaire
          )
        )
      )

  }
}
