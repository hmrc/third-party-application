Third Party Application
=======================

The Third Party Application microservice is responsible for maintaining the state of applications created
by (or on behalf of) third parties to consume APIs registered on the API Platform.

Application Search
------------------
Searching of Applications within the microservice is acheived by issuing a `GET` to the `/applications` endpoint, making use of the following Query Parameters:

Parameter Name    | Data Type/Allowed Values                                                                                       | Description
------------------|----------------------------------------------------------------------------------------------------------------|------------
`search`          | Free Text                                                                                                      | Search Application names and identifiers
`status`          | `CREATED`, `PENDING_GATEKEEPER_CHECK`, `PENDING_SUBMITTER_VERIFICATION`, `ACTIVE`                              | Retrieve Applications based on lifecycle status
`accessType`      | `STANDARD`, `ROPC`, `PRIVILEGED`                                                                               | Access type that Application uses
`apiSubscription` | `ANY`, `NONE`, or specific API name                                                                            | Which API the Application is subscribed to
`apiVersion`      | Version Number                                                                                                 | Version of the specific API that Application is subscribed to. Only relevant when the name of an API has been specified for `apiSubscription`
`lastUseBefore`   | ISO Representation of date and (optionally) time (e.g. `2020-01-01` and `2020-01-01T15:35:22Z` are both valid) | Applications that were last accessed *before* the specified date and time
`lastUseAfter`    | ISO Representation of date and (optionally) time (e.g. `2020-01-01` and `2020-01-01T15:35:22Z` are both valid) | Applications that were last accessed *after* the specified date and time

Additionally, the results can be sorted through use of the following query parameters:

Parameter Name | Data Type/Allowed Values                                                                    | Description
---------------|---------------------------------------------------------------------------------------------|------------
`sort`         | `NAME_ASC`, `NAME_DESC`, `SUBMITTED_ASC`, `SUBMITTED_DESC`, `LAST_USE_ASC`, `LAST_USE_DESC` | Sort by application name, creation data, or last use date, ascending or descending respectively. Defaults to `SUBMITTED_ASC` if not specified

Finally, the following query parameters are used to control the number of results returned:

Parameter Name | Data Type/Allowed Values                             | Description
---------------|------------------------------------------------------|------------
`page`         | Number (Defaults to `1` if not specified)            | The page number to display (based on `pageSize`)
`pageSize`     | Number (Defaults to `Int.MaxValue` if not specified) | The maximum number of results to return

### Examples

Request                                                    | Description
-----------------------------------------------------------|------------
`/applications`                                            | All Applications
`/applications?status=CREATED`                             | All Applications in testing
`/applications?search=foo`                                 | All Applications containing the text `foo` in either their name or id
`/applications?apiSubscription=test-service&apiVersion=v1` | All Applications subscribing to `v1` of `test-service` API
`/applications?accessType=STANDARD&page=1&pageSize=25`     | First 25 Applications using `Standard` access type

Hashing of Secrets
------------------
The microservice holds details of credentials that are used to generate OAuth2 tokens in the form of Client Secrets. The secrets are hashed in the database
using `bcrypt`. `Bcrypt` is an adaptive hashing function, meaning its resilience to brute force attacks can be adjusted by configuring the number of iterations
the hashing function goes through - the greater the number, the longer it takes exponentially to generate the hash. This is configured in the microservice 
using the `hashFunctionWorkFactor` configuration item.

Note, however, that there is a trade off to be made when configuring the work factor for the hashing function. Increasing the value will have a direct impact 
on the performance of token generation and, by extension, the API Platform as a whole. Any modifications to the value should be thoroughly performance tested 
prior to being put into External Test and Production. 

To assist, a scheduled job has been created - `BCryptPerfomanceMeasureJob` - that runs on a regular basis to show how long hashing using the various work 
factors will take, given the current underlying hardware the service is running on. Should hardware performance increase (e.g. if MDTP starts using higher 
performance AWS instances), this should be reflected here, and could be a signal that the work factor can be increased. 

If the configured work factor is changed, existing hashes within the database will still work automatically. However, the service will also re-hash any 
valid secrets received using the updated work factor value.

Tests
-----
Some tests require `MongoDB` to run.
Thus, remember to start up MongoDB if you want to run the tests locally.
The tests include unit tests and integration tests.
In order to run them, use this command line:

```
./run_all_tests.sh
```

A report will also be generated identifying any dependencies that need upgrading. This requires that
you have defined CATALOGUE_DEPENDENCIES_URL as an environment variable pointing to the dependencies
endpoint on the Tax Platform Catalogue's API.

Current Known Issues
--------------------
In some use cases, specifically if this microservice is running locally, we may not want to make calls to AWS API Gateway.
This can be disabled be entering application.conf and changing:

```
disableAwsCalls to true.
```
