Third Party Application
=======================

The Third Party Application microservice is responsible for maintaining the state of applications created
by (or on behalf of) third parties to consume APIs registered on the API Platform.

Application Search
------------------
See documentation for One Query Endpoint (OQE) as all other query endpoints are now deprecated and could be removed at any time.

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
