Third Party Application
=============

The Third Party Application microservice is responsible for maintaining the state of applications created
by (or on behalf of) third parties to consume APIs registered on the API Platform.

# Tests
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

#Current Known Issues
In some use cases, specifically if this microservice is running locally there may be a problem with Wso2.
This can be resolved be entering application.conf and changing:

```
Dev.skipWso2 to true.
```
