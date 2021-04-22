#
# Copyright (c) 2019, 2021, Oracle and/or its affiliates. All rights reserved.
# DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
#
# The Universal Permissive License (UPL), Version 1.0
#
# Subject to the condition set forth below, permission is hereby granted to any
# person obtaining a copy of this software, associated documentation and/or
# data (collectively the "Software"), free of charge and under any and all
# copyright rights in the Software, and any and all patent rights owned or
# freely licensable by each licensor hereunder covering either (i) the
# unmodified Software as contributed to or provided by such licensor, or (ii)
# the Larger Works (as defined below), to deal in both
#
# (a) the Software, and
#
# (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
# one is included with the Software each a "Larger Work" to which the Software
# is contributed by such licensors),
#
# without restriction, including without limitation the rights to copy, create
# derivative works of, display, perform, and distribute the Software and make,
# use, sell, offer for sale, import, export, have made, and have sold the
# Software and the Larger Work(s), and to sublicense the foregoing rights on
# either these or other terms.
#
# This license is subject to the following condition:
#
# The above copyright notice and either this complete permission notice or at a
# minimum a reference to the UPL must be included in all copies or substantial
# portions of the Software.
#
# THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
# IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
# FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
# AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
# LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
# OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
# SOFTWARE.
#

from __future__ import print_function

import sys
import os.path
import time
import signal
import threading
import json
import argparse
import mx
import mx_benchmark
import datetime

def parse_prefixed_args(prefix, args):
    ret = []
    for arg in args:
        if arg.startswith(prefix):
            parsed = arg.split(' ')[0].split(prefix)[1]
            if parsed not in ret:
                ret.append(parsed)
    return ret

def parse_prefixed_arg(prefix, args, errorMsg):
    ret = parse_prefixed_args(prefix, args)
    if len(ret) > 1:
        mx.abort(errorMsg)
    elif len(ret) < 1:
        return None
    else:
        return ret[0]


def urllib():
    try:
        if sys.version_info < (3, 0):
            import urllib2 as urllib
        else:
            import urllib.request as urllib
        return urllib
    except ImportError:
        mx.abort("Failed to import dependency module: urllib")


class NativeImageBenchmarkMixin(object):

    def __init__(self):
        self.benchmark_name = None

    def benchmarkName(self):
        if not self.benchmark_name:
            raise NotImplementedError()
        return self.benchmark_name

    def run_stage(self, stage, command, out, err, cwd, nonZeroIsFatal):
        return mx.run(command, out=out, err=err, cwd=cwd, nonZeroIsFatal=nonZeroIsFatal)

    def extra_image_build_argument(self, _, args):
        return parse_prefixed_args('-Dnative-image.benchmark.extra-image-build-argument=', args)

    def extra_run_arg(self, _, args):
        return parse_prefixed_args('-Dnative-image.benchmark.extra-run-arg=', args)

    def extra_agent_run_arg(self, _, args):
        return parse_prefixed_args('-Dnative-image.benchmark.extra-agent-run-arg=', args)

    def extra_profile_run_arg(self, _, args):
        return parse_prefixed_args('-Dnative-image.benchmark.extra-profile-run-arg=', args)

    def extra_agent_profile_run_arg(self, _, args):
        return parse_prefixed_args('-Dnative-image.benchmark.extra-agent-profile-run-arg=', args)

    def benchmark_output_dir(self, _, args):
        parsed_args = parse_prefixed_args('-Dnative-image.benchmark.benchmark-output-dir=', args)
        if parsed_args:
            return parsed_args[0]
        else:
            return None

    def stages(self, args):
        parsed_arg = parse_prefixed_arg('-Dnative-image.benchmark.stages=', args, 'Native Image benchmark stages should only be specified once.')
        return parsed_arg.split(',') if parsed_arg else ['agent', 'instrument-image', 'instrument-run', 'image', 'run']

    def skip_agent_assertions(self, _, args):
        parsed_args = parse_prefixed_args('-Dnative-image.benchmark.skip-agent-assertions=', args)
        if 'true' in parsed_args or 'True' in parsed_args:
            return True
        elif 'false' in parsed_args or 'False' in parsed_args:
            return False
        else:
            return None

    def skip_build_assertions(self, _):
        return False


def timeToFirstResponse(cmd, bmSuite):
    def timeToFirstResponseThread(startTime):
        protocolHost = bmSuite.serviceHost()
        servicePath = bmSuite.serviceEndpoint()
        if not (protocolHost.startswith('http') or protocolHost.startswith('https')):
            protocolHost = "http://" + protocolHost
        if not (servicePath.startswith('/') or protocolHost.endswith('/')):
            servicePath = '/' + servicePath
        url = "{}:{}{}".format(protocolHost, bmSuite.servicePort(), servicePath)
        for _ in range(60*100):
            try:
                req = urllib().urlopen(url)
                if req.getcode() == 200:
                    finishTime = datetime.datetime.now()
                    msToFirstResponse = (finishTime - startTime).total_seconds() * 1000
                    bmSuite.timeToFirstResponseOutput = "First response received in {} ms".format(msToFirstResponse)
                    mx.log(bmSuite.timeToFirstResponseOutput)
                    return
            except IOError:
                time.sleep(.01)
        mx.abort("Failed measure time to first response. Service not reachable at " + url)
    name = bmSuite.benchMicroserviceName()
    if name in ''.join(cmd):
        threading.Thread(target=timeToFirstResponseThread, args=[datetime.datetime.now()]).start()
    return cmd


class BaseMicroserviceBenchmarkSuite(mx_benchmark.JavaBenchmarkSuite, NativeImageBenchmarkMixin):
    """
    Base class for Microservice benchmark suites. A Microservice is an application that opens a port that is ready to
    receive requests. This benchmark suite runs a tester process in the background (such as JMeter or Wrk2) and run a
    Microservice application in foreground. Once the tester finishes stress testing the application, the tester process
    terminates and the application is killed with SIGTERM.
    """

    def __init__(self):
        super(BaseMicroserviceBenchmarkSuite, self).__init__()
        self.testerOutput = ''
        self.timeToFirstResponseOutput = ''
        self.bmSuiteArgs = None
        self.workloadPath = None
        self.parser = argparse.ArgumentParser()
        self.parser.add_argument(
            "--workload-configuration", type=str, default=None, help="Path to workload configuration.")
        self.register_command_mapper_hook("TimeToFirstResponse", timeToFirstResponse)

    def benchSuiteName(self):
        return self.name()

    def benchMicroserviceName(self):
        """
        Returns the microservice name. The convention here is that the benchmark name contains two elements separated
        by a hyphen ('-'):
        - the microservice name (shopcart, for example);
        - the tester tool name (jmeter, for example).

        :return: Microservice name.
        :rtype: str
        """

        if len(self.benchSuiteName().split('-')) < 2:
            mx.abort("Invalid benchmark suite name: " + self.benchSuiteName())
        return self.benchSuiteName().split("-")[0]

    def defaultWorkloadPath(self, benchmarkName):
        """Returns the workload configuration path.

        :return: Path to configuration file.
        :rtype: str
        """
        raise NotImplementedError()

    def workloadConfigurationPath(self):
        if self.workloadPath:
            mx.log("Using user-provided workload configuration file: {0}".format(self.workloadPath))
            return self.workloadPath
        else:
            return self.defaultWorkloadPath(self.benchmarkName())

    def applicationPath(self):
        """Returns the application Jar path.

        :return: Path to Jar.
        :rtype: str
        """
        raise NotImplementedError()

    def serviceHost(self):
        """Returns the microservice host.

        :return: Host used to access the microservice.
        :rtype: str
        """
        return 'localhost'

    def servicePort(self):
        """Returns the microservice port.

        :return: Port that the microservice is using to receive requests.
        :rtype: int
        """
        return 8080

    def serviceEndpoint(self):
        """Returns the microservice path that checks if the service is running.

        :return: service path
        :rtype: str
        """
        return ''

    def inNativeMode(self):
        return self.jvm(self.bmSuiteArgs) == "native-image"

    def createCommandLineArgs(self, benchmarks, bmSuiteArgs):
        return self.vmArgs(bmSuiteArgs) + ["-jar", self.applicationPath()]

    @staticmethod
    def waitForPort(port, timeout=10):
        try:
            import psutil
        except ImportError:
            # Note: abort fails to find the process (not registered yet in mx) if we are too fast failing here.
            time.sleep(5)
            mx.abort("Failed to import {0} dependency module: psutil".format(BaseMicroserviceBenchmarkSuite.__name__))
        for _ in range(timeout + 1):
            for proc in psutil.process_iter():
                try:
                    for conns in proc.connections(kind='inet'):
                        if conns.laddr.port == port:
                            return proc
                except:
                    pass
            time.sleep(1)
        return None

    def runAndReturnStdOut(self, benchmarks, bmSuiteArgs):
        ret_code, applicationOutput, dims = super(BaseMicroserviceBenchmarkSuite, self).runAndReturnStdOut(benchmarks, bmSuiteArgs)
        return ret_code, self.testerOutput.underlying.data + self.timeToFirstResponseOutput + applicationOutput, dims

    @staticmethod
    def terminateApplication(port):
        proc = BaseMicroserviceBenchmarkSuite.waitForPort(port, 0)
        if proc:
            proc.send_signal(signal.SIGTERM)
            return True
        else:
            return False

    @staticmethod
    def runTesterInBackground(benchmarkSuite):
        if not BaseMicroserviceBenchmarkSuite.waitForPort(benchmarkSuite.servicePort()):
            mx.abort("Failed to find server application in {0}".format(BaseMicroserviceBenchmarkSuite.__name__))
        benchmarkSuite.runTester()
        if not BaseMicroserviceBenchmarkSuite.terminateApplication(benchmarkSuite.servicePort()):
            mx.abort("Failed to terminate server application in {0}".format(BaseMicroserviceBenchmarkSuite.__name__))

    def run_stage(self, stage, server_command, out, err, cwd, nonZeroIsFatal):
        if 'image' in stage:
            # For image stages, we just run the given command
            return super(BaseMicroserviceBenchmarkSuite, self).run_stage(stage, server_command, out, err, cwd, nonZeroIsFatal)
        else:
            # For run stages, we need to run the server and the loader
            threading.Thread(target=BaseMicroserviceBenchmarkSuite.runTesterInBackground, args=[self]).start()
            if 'agent' in stage:
                timeToFirstResponse(server_command, self)
            return mx.run(server_command, out=out, err=err, cwd=cwd, nonZeroIsFatal=nonZeroIsFatal)

    def rules(self, output, benchmarks, bmSuiteArgs):
        return [
            mx_benchmark.StdOutRule(
                r"^First response received in (?P<startup>\d*[.,]?\d*) ms",
                {
                    "benchmark": benchmarks[0],
                    "bench-suite": self.benchSuiteName(),
                    "metric.name": "startup",
                    "metric.value": ("<startup>", float),
                    "metric.unit": "ms",
                    "metric.better": "lower",
                }
            )
        ]


    def run(self, benchmarks, bmSuiteArgs):
        if len(benchmarks) > 1:
            mx.abort("A single benchmark should be specified for {0}.".format(BaseMicroserviceBenchmarkSuite.__name__))
        self.bmSuiteArgs = bmSuiteArgs
        self.benchmark_name = benchmarks[0]
        args, remainder = self.parser.parse_known_args(self.bmSuiteArgs)
        self.workloadPath = args.workload_configuration
        if not self.inNativeMode():
            threading.Thread(target=BaseMicroserviceBenchmarkSuite.runTesterInBackground, args=[self]).start()
        results = super(BaseMicroserviceBenchmarkSuite, self).run(benchmarks, remainder)
        return results


class BaseJMeterBenchmarkSuite(BaseMicroserviceBenchmarkSuite, mx_benchmark.AveragingBenchmarkMixin):
    """Base class for JMeter based benchmark suites."""

    def jmeterVersion(self):
        return '5.3'

    def rules(self, out, benchmarks, bmSuiteArgs):
        # Example of jmeter output:
        # "summary =     70 in 00:00:01 =   47.6/s Avg:    12 Min:     3 Max:   592 Err:     0 (0.00%)"
        return [
            mx_benchmark.StdOutRule(
                r"^summary \+\s+(?P<requests>[0-9]+) in (?P<hours>\d+):(?P<minutes>\d\d):(?P<seconds>\d\d) =\s+(?P<throughput>\d*[.,]?\d*)/s Avg:\s+(?P<avg>\d+) Min:\s+(?P<min>\d+) Max:\s+(?P<max>\d+) Err:\s+(?P<errors>\d+) \((?P<errpct>\d*[.,]?\d*)\%\)", # pylint: disable=line-too-long
                {
                    "benchmark": benchmarks[0],
                    "bench-suite": self.benchSuiteName(),
                    "metric.name": "warmup",
                    "metric.value": ("<throughput>", float),
                    "metric.unit": "op/s",
                    "metric.better": "higher",
                    "metric.iteration": ("$iteration", int),
                    "warnings": ("<errors>", str),
                }
            )
        ] + super(BaseJMeterBenchmarkSuite, self).rules(out, benchmarks, bmSuiteArgs)

    def runTester(self):
        jmeterDirectory = mx.library("APACHE_JMETER_" + self.jmeterVersion(), True).get_path(True)
        jmeterPath = os.path.join(jmeterDirectory, "apache-jmeter-" + self.jmeterVersion(), "bin/ApacheJMeter.jar")
        jmeterCmd = [mx.get_jdk().java, "-jar", jmeterPath, "-n", "-t", self.workloadConfigurationPath(), "-j", "/dev/stdout"] # pylint: disable=line-too-long
        mx.log("Running JMeter: {0}".format(jmeterCmd))
        self.testerOutput = mx.TeeOutputCapture(mx.OutputCapture())
        mx.run(jmeterCmd, out=self.testerOutput, err=self.testerOutput)

    def tailDatapointsToSkip(self, results):
        return int(len(results) * .10)

    def run(self, benchmarks, bmSuiteArgs):
        results = super(BaseJMeterBenchmarkSuite, self).run(benchmarks, bmSuiteArgs)
        results = results[:len(results) - self.tailDatapointsToSkip(results)]
        self.addAverageAcrossLatestResults(results, "throughput")
        return results


class BaseWrkBenchmarkSuite(BaseMicroserviceBenchmarkSuite):
    """Base class for Wrk based benchmark suites."""

    def loadConfiguration(self, benchmarkName):
        """Returns a json object that describes the Wrk configuration. The following syntax is expected:
        {
          "connections" : <number of connections to keep open>,
          "script" : <path to lua script to be used>,
          "threads" : <number of threads to use>,
          "duration" : <duration of the test, for example "30s">,
          "warmup-duration" : <duration of the warmup run, for example "30s">,
          "rate" : <work rate (requests per second)>,
          "target-url" : <URL to target, for example "http://localhost:8080">,
          "target-path" : <path to append to the target URL>
        }

        All json fields are optional except the rate and the target-url.

        :return: Configuration json.
        :rtype: json
        """
        with open(self.workloadConfigurationPath()) as configFile:
            config = json.load(configFile)
            mx.log("Loading configuration file for {0}: {1}".format(BaseWrkBenchmarkSuite.__name__, configFile.name))
            return config

    def getScriptPath(self, config):
        pass

    def getLibraryDirectory(self):
        """Returns the wrk library directory.

        :return: Path to extracted wrk package.
        :rtype: str
        """
        raise NotImplementedError()

    def setupWrkCmd(self, config, cmd=[]): # pylint: disable=dangerous-default-value
        for optional in ["connections", "threads"]:
            if optional in config:
                cmd += ["--" + optional, str(config[optional])]

        if "script" in config:
            cmd += ["--script", str(self.getScriptPath(config))]

        if "target-url" in config:
            if "target-path" in config:
                cmd.append(str(config["target-url"] + config["target-path"]))
            else:
                cmd.append(str(config["target-url"]))
        else:
            mx.abort("target-url not specified in Wrk configuration.")
        return cmd

    def runTester(self):
        config = self.loadConfiguration(self.benchmarkName())
        wrkDirectory = self.getLibraryDirectory()
        if mx.get_os() == "linux":
            distro = "linux"
        elif mx.get_os() == "darwin":
            distro = "macos"
        else:
            mx.abort("{0} not supported in {1}.".format(BaseWrkBenchmarkSuite.__name__, mx.get_os()))

        wrkPath = os.path.join(wrkDirectory, "wrk-{os}".format(os=distro))
        wrkFlags = self.setupWrkCmd(config)

        warmupDuration = None
        if self.inNativeMode():
            warmupDuration = config.get("warmup-duration-native-image", None)
        elif "warmup-duration" in config:
            warmupDuration = config["warmup-duration"]
        if warmupDuration:
            warmupWrkCmd = [wrkPath] + ["--duration", str(warmupDuration)] + wrkFlags
            mx.log("Warming up with Wrk: {0}".format(warmupWrkCmd))
            warmupOutput = mx.TeeOutputCapture(mx.OutputCapture())
            mx.run(warmupWrkCmd, out=warmupOutput, err=warmupOutput)

        if "duration" in config:
            wrkFlags = ["--duration", str(config["duration"])] + wrkFlags

        runWrkCmd = [wrkPath] + wrkFlags
        mx.log("Running Wrk: {0}".format(runWrkCmd))
        self.testerOutput = mx.TeeOutputCapture(mx.OutputCapture())
        mx.run(runWrkCmd, out=self.testerOutput, err=self.testerOutput)


class BaseWrk1BenchmarkSuite(BaseWrkBenchmarkSuite):
    """
    Base class for Wrk based benchmark suites. Wrk (https://github.com/wg/wrk) is a tool that can be used to measure
    the throughput of applications offering HTTP services.
    """

    def rules(self, out, benchmarks, bmSuiteArgs):
        # Example of wrk output:
        # "Requests/sec:   5453.61"
        return [
            mx_benchmark.StdOutRule(
                r"^Requests/sec:\s*(?P<throughput>\d*[.,]?\d*)$",
                {
                    "benchmark": benchmarks[0],
                    "bench-suite": self.benchSuiteName(),
                    "metric.name": "throughput",
                    "metric.value": ("<throughput>", float),
                    "metric.unit": "op/s",
                    "metric.better": "higher",
                }
            )
        ] + super(BaseWrk1BenchmarkSuite, self).rules(out, benchmarks, bmSuiteArgs)

    def getLibraryDirectory(self):
        return mx.library("WRK", True).get_path(True)


class BaseWrk2BenchmarkSuite(BaseWrkBenchmarkSuite):
    """
    Base class for Wrk2 based benchmark suites. Wrk2 (https://github.com/giltene/wrk2) is a tool that can be used to
    measure the latency of applications offering HTTP services.
    """

    def rules(self, out, benchmarks, bmSuiteArgs):
        # Example of wrk2 output:
        # " 50.000%  3.24ms"
        return [
            mx_benchmark.StdOutRule(
                r"^\s*(?P<percentile>\d*[.,]?\d*)%\s+(?P<latency>\d*[.,]?\d*)ms$",
                {
                    "benchmark": benchmarks[0],
                    "bench-suite": self.benchSuiteName(),
                    "metric.name": "sample-time",
                    "metric.value": ("<latency>", float),
                    "metric.unit": "ms",
                    "metric.better": "lower",
                    "metric.percentile": ("<percentile>", float),
                }
            )
        ] + super(BaseWrk2BenchmarkSuite, self).rules(out, benchmarks, bmSuiteArgs)

    def getLibraryDirectory(self):
        return mx.library("WRK2", True).get_path(True)

    def setupWrkCmd(self, config):
        cmd = ["--latency"]
        if "rate" in config:
            cmd += ["--rate", str(config["rate"])]
        else:
            mx.abort("rate not specified in Wrk2 configuration.")
        return super(BaseWrk2BenchmarkSuite, self).setupWrkCmd(config, cmd)
