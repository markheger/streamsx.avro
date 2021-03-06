# streamsx.avro tests

## Before launching the test

Ensure that you have Python 3.6 installed. For example, you can get Python 3.6 from the [Anaconda archive page](https://repo.continuum.io/archive/index.html).

Ensure that the bin directory is added to the PATH environment variable. If necessary, add the bin directory by entering the following command on the command line:

    export PATH="~/anaconda3/bin:$PATH"

Ensure that you have set the following environment variables for testing with Streaming Analytics service in IBM Cloud:

* `STREAMING_ANALYTICS_SERVICE_NAME` - name of your Streaming Analytics service
* `VCAP_SERVICES` - [VCAP](https://console.bluemix.net/docs/services/StreamingAnalytics/r_vcap_services.html#r_vcap_services) information in JSON format or a JSON file

Ensure that you have set the following environment variables for testing with local Streams instance:

* `STREAMS_USERNAME` - username to connect with local Streams domain
* `STREAMS_PASSWORD` - password to connect with local Streams domain

Ensure that you have set the following environment variables for testing with Cloud Pak for Data Streams instance:

* `CP4D_URL`
* `STREAMS_INSTANCE_ID`
* `STREAMS_USERNAME`
* `STREAMS_PASSWORD`

### Required Python packages

Python unit test requires TopologyTester from Python streamsx package or com.ibm.streamsx.topology toolkit version 1.13.5 or later.

Install the latest streamsx package with pip, a package manager for Python, by entering the following command on the command line:

    pip install --upgrade streamsx



## Run the test with Streaming Analytics service and toolkit from this repository

    python3 -u -m unittest test_avro.TestCloud


## Run the test with Local Streams instance and toolkit from this repository

    python3 -u -m unittest test_avro.Test

## Run the test with CP4D Streams instance and toolkit from this repository

    python3 -u -m unittest test_avro.TestICP

