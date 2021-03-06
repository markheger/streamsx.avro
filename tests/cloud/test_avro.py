import unittest

from streamsx.topology.topology import *
from streamsx.topology.tester import Tester
import streamsx.spl.op as op
import streamsx.spl.toolkit as tk
import streamsx.rest as sr
import os, os.path
import urllib3

import streamsx.topology.context
import requests
from urllib.parse import urlparse

class Test(unittest.TestCase):
    """ Test invocations of composite operators in local Streams instance """

    @classmethod
    def setUpClass(self):
        print (str(self))
        urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)
        self.streams_install = os.environ.get('STREAMS_INSTALL')
        if self.streams_install is not None:
            self.json_toolkit_location = self.streams_install+'/toolkits/com.ibm.streamsx.json'

    def setUp(self):
        Tester.setup_distributed(self)
        self.isCloudTest = False
        if os.environ.get('STREAMSX_AVRO_TOOLKIT') is None:
            self.avro_toolkit_location = "../../com.ibm.streamsx.avro"
        else:
            self.avro_toolkit_location = os.environ.get('STREAMSX_AVRO_TOOLKIT')

    def _add_toolkits(self, topo, test_toolkit):
        tk.add_toolkit(topo, test_toolkit)
        if self.avro_toolkit_location is not None:
            tk.add_toolkit(topo, self.avro_toolkit_location)
        if self.json_toolkit_location is not None:
            tk.add_toolkit(topo, self.json_toolkit_location)

    def _build_launch_app(self, name, composite_name, parameters, num_result_tuples, test_toolkit):
        print ("------ "+name+" ------")
        topo = Topology(name)
        self._add_toolkits(topo, test_toolkit)

        params = parameters
        # Call the test composite
        test_op = op.Source(topo, composite_name, 'tuple<rstring result>', params=params)
        self.tester = Tester(topo)
        self.tester.run_for(30)
        self.tester.tuple_count(test_op.stream, num_result_tuples, exact=False)

        cfg = {}

        # change trace level
        job_config = streamsx.topology.context.JobConfig(tracing='warn')
        job_config.add(cfg)

        if ("Cloud" not in str(self)):
            cfg[streamsx.topology.context.ConfigParams.SSL_VERIFY] = False

        # Run the test
        test_res = self.tester.test(self.test_ctxtype, cfg, assert_on_fail=True, always_collect_logs=True)
        print (str(self.tester.result))
        assert test_res, name+" FAILED ("+self.tester.result["application_logs"]+")"


    # ------------------------------------

    def test_avro_sample(self):
        self._build_launch_app("test_avro_sample", "com.ibm.streamsx.avro.sample::AvroJSONSampleComp", {}, 1, 'avro_test')

    # ------------------------------------


class TestLocal(Test):
    """ Test invocations of composite operators in local Streams instance using installed toolkit """

    def setUp(self):
        Tester.setup_distributed(self)
        self.streams_install = os.environ.get('STREAMS_INSTALL')
        self.avro_toolkit_location = self.streams_install+'/toolkits/com.ibm.streamsx.avro'


class TestICP(Test):
    """ Test in CP4D env using local toolkit (repo) """

    @classmethod
    def setUpClass(self):
        super().setUpClass()
        env_chk = True
        try:
            print("CP4D_URL="+str(os.environ['CP4D_URL']))
        except KeyError:
            env_chk = False
        assert env_chk, "CP4D_URL environment variable must be set"


class TestICPLocal(TestICP):
    """ Test in CP4D env using local installed toolkit (STREAMS_INSTALL/toolkits) """

    @classmethod
    def setUpClass(self):
        super().setUpClass()

    def setUp(self):
        Tester.setup_distributed(self)
        self.streams_install = os.environ.get('STREAMS_INSTALL')
        self.avro_toolkit_location = self.streams_install+'/toolkits/com.ibm.streamsx.avro'


class TestICPRemote(TestICP):
    """ Test in CP4D env using remote toolkit (build service) """

    @classmethod
    def setUpClass(self):
        super().setUpClass()

    def setUp(self):
        Tester.setup_distributed(self)
        self.avro_toolkit_location = None
        self.json_toolkit_location = None

class TestCloud(Test):
    """ Test in Streaming Analytics Service using local toolkit (repo) """

    @classmethod
    def setUpClass(self):
        super().setUpClass()
        # start streams service
        connection = sr.StreamingAnalyticsConnection()
        service = connection.get_streaming_analytics()
        result = service.start_instance()

    def setUp(self):
        Tester.setup_streaming_analytics(self, force_remote_build=False)
        if os.environ.get('STREAMSX_AVRO_TOOLKIT') is None:
            self.avro_toolkit_location = "../../com.ibm.streamsx.avro"
        else:
            self.avro_toolkit_location = os.environ.get('STREAMSX_AVRO_TOOLKIT')


class TestCloudLocal(TestCloud):
    """ Test in Streaming Analytics Service using local installed toolkit """

    @classmethod
    def setUpClass(self):
        super().setUpClass()

    def setUp(self):
        Tester.setup_streaming_analytics(self, force_remote_build=False)
        self.streams_install = os.environ.get('STREAMS_INSTALL')
        self.avro_toolkit_location = self.streams_install+'/toolkits/com.ibm.streamsx.avro'


class TestCloudLocalRemote(TestCloud):
    """ Test in Streaming Analytics Service using local toolkit from repo and remote build """

    @classmethod
    def setUpClass(self):
        super().setUpClass()

    def setUp(self):
        Tester.setup_streaming_analytics(self, force_remote_build=True)
        self.avro_toolkit_location = "../../com.ibm.streamsx.avro"
        self.json_toolkit_location = os.environ["JSON_TOOLKIT_HOME"]


class TestCloudRemote(TestCloud):
    """ Test in Streaming Analytics Service using remote toolkit and remote build """

    @classmethod
    def setUpClass(self):
        super().setUpClass()

    def setUp(self):
        Tester.setup_streaming_analytics(self, force_remote_build=True)
        # remote toolkit is used
        self.avro_toolkit_location = None
        self.json_toolkit_location = None
