#
# Copyright (c) 2016, 2021, Oracle and/or its affiliates.
#
# All rights reserved.
#
# Redistribution and use in source and binary forms, with or without modification, are
# permitted provided that the following conditions are met:
#
# 1. Redistributions of source code must retain the above copyright notice, this list of
# conditions and the following disclaimer.
#
# 2. Redistributions in binary form must reproduce the above copyright notice, this list of
# conditions and the following disclaimer in the documentation and/or other materials provided
# with the distribution.
#
# 3. Neither the name of the copyright holder nor the names of its contributors may be used to
# endorse or promote products derived from this software without specific prior written
# permission.
#
# THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS
# OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
# MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
# COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
# EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
# GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED
# AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
# NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
# OF THE POSSIBILITY OF SUCH DAMAGE.
#
from __future__ import print_function

import fnmatch
import pipes
import shutil

import mx
import mx_native
import mx_unittest
import mx_subst
import os

import mx_sulong

import sys

if sys.version_info[0] < 3:
    _unicode = unicode # pylint: disable=undefined-variable
else:
    _unicode = str

_basestring = (str, _unicode)


def run(vmArgs, unittests, extraOption=None, extraLibs=None):
    if not isinstance(unittests, list):
        unittests = [unittests]
    if extraOption is None:
        extraOption = []
    if mx.get_opts().verbose:
        command = mx_sulong.getCommonOptions(True, extraLibs) + extraOption + vmArgs + ['--very-verbose'] + unittests
        print('Running mx unittests ' + ' '.join(command))
        return mx_unittest.unittest(command)
    else:
        command = mx_sulong.getCommonOptions(True, extraLibs) + extraOption + vmArgs + unittests
        return mx_unittest.unittest(command)


def compileTestSuite(testsuiteproject, extra_build_args):
    defaultBuildArgs = ['--project', testsuiteproject]
    mx.command_function('build')(defaultBuildArgs + extra_build_args)


class DragonEggSupport:
    """Helpers for DragonEgg

    DragonEgg support is controlled by two environment variables:
      * `DRAGONEGG_GCC`: path to a GCC installation with dragonegg support
      * `DRAGONEGG_LLVM`: path to an LLVM installation that can deal with bitcode produced by DragonEgg
      * `DRAGONEGG`: (optional) path to folder that contains the `libdragonegg.so`
    """

    @staticmethod
    def haveDragonegg():
        if not hasattr(DragonEggSupport, '_haveDragonegg'):
            DragonEggSupport._haveDragonegg = DragonEggSupport.pluginPath() is not None and os.path.exists(
                DragonEggSupport.pluginPath()) and DragonEggSupport.findGCCProgram('gcc', optional=True) is not None
        return DragonEggSupport._haveDragonegg

    @staticmethod
    def pluginPath():
        if 'DRAGONEGG' in os.environ:
            return os.path.join(os.environ['DRAGONEGG'], mx.add_lib_suffix('dragonegg'))
        if 'DRAGONEGG_GCC' in os.environ:
            path = os.path.join(os.environ['DRAGONEGG_GCC'], 'lib', mx.add_lib_suffix('dragonegg'))
            if os.path.exists(path):
                return path
        return None

    @staticmethod
    def findLLVMProgram(program, optional=False):
        if 'DRAGONEGG_LLVM' in os.environ:
            path = os.environ['DRAGONEGG_LLVM']
            return os.path.join(path, 'bin', program)
        if optional:
            return None
        mx.abort("Cannot find LLVM program for dragonegg: {}\nDRAGONEGG_LLVM environment variable not set".format(program))

    @staticmethod
    def findGCCProgram(gccProgram, optional=False):
        if 'DRAGONEGG_GCC' in os.environ:
            path = os.environ['DRAGONEGG_GCC']
            return os.path.join(path, 'bin', gccProgram)
        if optional:
            return None
        mx.abort("Cannot find GCC program for dragonegg: {}\nDRAGONEGG_GCC environment variable not set".format(gccProgram))


class SulongTestSuiteBuildTask(mx.NativeBuildTask):
    """Track whether we are checking if a build is required or actually building."""
    def needsBuild(self, newestInput):
        try:
            self.subject._is_needs_rebuild_call = True
            return super(SulongTestSuiteBuildTask, self).needsBuild(newestInput)
        finally:
            self.subject._is_needs_rebuild_call = False


class SulongTestSuiteBase(mx.NativeProject):  # pylint: disable=too-many-ancestors
    def __init__(self, suite, name, subDir, deps, workingSets, results, output, d, **args):
        super(SulongTestSuiteBase, self).__init__(suite, name, subDir, [], deps, workingSets, results, output, d, **args)

    def getVariants(self):
        if not hasattr(self, '_variants'):
            self._variants = []
            for v in self.variants:
                if 'gcc' in v and not DragonEggSupport.haveDragonegg():
                    mx.warn('Could not find dragonegg, not building test variant "%s"' % v)
                    continue
                self._variants.append(v)
        return self._variants

    def getResults(self, replaceVar=mx_subst.results_substitutions):
        if not self.results:
            self.results = []
            for t in self.getTests():
                if self.buildRef:
                    self.results.append(os.path.join(t, 'ref.out'))
                for v in self.getVariants():
                    result_file = mx.add_lib_suffix(v) if self.buildSharedObject else v + '.bc'
                    self.results.append(os.path.join(t, result_file))
        return super(SulongTestSuiteBase, self).getResults(replaceVar=replaceVar)


class SulongTestSuite(SulongTestSuiteBase):  # pylint: disable=too-many-ancestors
    def __init__(self, suite, name, deps, workingSets, subDir, results=None, output=None, buildRef=True,
                 buildSharedObject=False, bundledLLVMOnly=False, **args):
        projectDir = args.pop('dir', None)
        if projectDir:
            d_rel = projectDir
        elif subDir is None:
            d_rel = name
        else:
            d_rel = os.path.join(subDir, name)
        d = os.path.join(suite.dir, d_rel.replace('/', os.sep))
        super(SulongTestSuite, self).__init__(suite, name, subDir, deps, workingSets, results, output, d, **args)
        if bundledLLVMOnly and mx.get_env('CLANG_CC', None):
            self.ignore = "Environment variable 'CLANG_CC' is set but project specifies 'bundledLLVMOnly'"
        self.vpath = True
        self.buildRef = buildRef
        self.buildSharedObject = buildSharedObject
        self._is_needs_rebuild_call = False
        if not hasattr(self, 'testClasses'):
            self.testClasses = self.defaultTestClasses()

    def getBuildTask(self, args):
        return SulongTestSuiteBuildTask(args, self)

    def defaultTestClasses(self):
        return ["SulongSuite"]

    def getTests(self):
        if not hasattr(self, '_tests'):
            self._tests = []
            # collect tests from VPATH (defaults to self.dir)
            root = os.path.join(self._get_vpath())
            for path, _, files in os.walk(root):
                for f in files:
                    absPath = os.path.join(path, f)
                    relPath = os.path.relpath(absPath, root)
                    _, ext = os.path.splitext(relPath)
                    if ext in getattr(self, "fileExts", ['.c', '.cpp', '.ll']):
                        self._tests.append(relPath + ".dir")
        return self._tests

    def _get_vpath(self):
        env = super(SulongTestSuite, self).getBuildEnv()
        return env.get('VPATH', self.dir)

    def getBuildEnv(self, replaceVar=mx_subst.path_substitutions):
        env = super(SulongTestSuite, self).getBuildEnv(replaceVar=replaceVar)
        env['PROJECT'] = self.name
        env['TESTS'] = ' '.join(self.getTests())
        env['VARIANTS'] = ' '.join(self.getVariants())
        env['BUILD_REF'] = '1' if self.buildRef else '0'
        env['BUILD_SO'] = '1' if self.buildSharedObject else '0'
        env['SO_EXT'] = mx.add_lib_suffix("")
        env['CLANG'] = mx_sulong.findBundledLLVMProgram('clang')
        env['CLANGXX'] = mx_sulong.findBundledLLVMProgram('clang++')
        env['LLVM_OPT'] = mx_sulong.findBundledLLVMProgram('opt')
        env['LLVM_AS'] = mx_sulong.findBundledLLVMProgram('llvm-as')
        env['LLVM_DIS'] = mx_sulong.findBundledLLVMProgram('llvm-dis')
        env['LLVM_LINK'] = mx_sulong.findBundledLLVMProgram('llvm-link')
        env['LLVM_OBJCOPY'] = mx_sulong.findBundledLLVMProgram('llvm-objcopy')
        env['GRAALVM_LLVM_HOME'] = mx_subst.path_substitutions.substitute("<path:SULONG_HOME>")
        if 'OS' not in env:
            env['OS'] = mx_subst.path_substitutions.substitute("<os>")
        if DragonEggSupport.haveDragonegg():
            env['DRAGONEGG'] = DragonEggSupport.pluginPath()
            env['DRAGONEGG_GCC'] = DragonEggSupport.findGCCProgram('gcc', optional=False)
            env['DRAGONEGG_LLVMAS'] = DragonEggSupport.findLLVMProgram("llvm-as")
            env['DRAGONEGG_FC'] = DragonEggSupport.findGCCProgram('gfortran', optional=False)
            env['FC'] = DragonEggSupport.findGCCProgram('gfortran', optional=False)
        elif not self._is_needs_rebuild_call and getattr(self, 'requireDragonegg', False):
            mx.abort('Could not find dragonegg, cannot build "{}" (requireDragonegg = True).'.format(self.name))
        return env


def llirtestgen(args=None, out=None):
    return mx.run_java(mx.get_runtime_jvm_args(["LLIR_TEST_GEN"]) + ["com.oracle.truffle.llvm.tests.llirtestgen.LLIRTestGen"] + args, out=out)


class GeneratedTestSuite(SulongTestSuiteBase):  # pylint: disable=too-many-ancestors
    def __init__(self, suite, name, deps, workingSets, subDir, results=None, output=None, buildRef=True,
                 buildSharedObject=False, **args):
        d = os.path.join(suite.dir, subDir, name)
        super(GeneratedTestSuite, self).__init__(suite, name, subDir, deps, workingSets, results, output, d, **args)
        self.vpath = True
        self.buildRef = buildRef
        self.buildSharedObject = buildSharedObject
        self._is_needs_rebuild_call = False

    def getTests(self):
        if not hasattr(self, '_tests'):
            self._tests = []

            def enlist(line):
                line = line.strip()
                if not line.endswith(".ignore"):
                    self._tests += [line + ".dir"]

            llirtestgen(["gen", "--print-filenames"], out=enlist)
        return self._tests

    def getBuildEnv(self, replaceVar=mx_subst.path_substitutions):
        env = super(GeneratedTestSuite, self).getBuildEnv(replaceVar=replaceVar)
        env['VPATH'] = self.dir
        env['PROJECT'] = self.name
        env['TESTS'] = ' '.join(self.getTests())
        env['VARIANTS'] = ' '.join(self.getVariants())
        env['BUILD_REF'] = '1' if self.buildRef else '0'
        env['BUILD_SO'] = '1' if self.buildSharedObject else '0'
        env['SO_EXT'] = mx.add_lib_suffix("")
        env['CLANG'] = mx_sulong.findBundledLLVMProgram('clang')
        env['CLANGXX'] = mx_sulong.findBundledLLVMProgram('clang++')
        env['LLVM_OPT'] = mx_sulong.findBundledLLVMProgram('opt')
        env['LLVM_AS'] = mx_sulong.findBundledLLVMProgram('llvm-as')
        env['LLVM_DIS'] = mx_sulong.findBundledLLVMProgram('llvm-dis')
        env['LLVM_LINK'] = mx_sulong.findBundledLLVMProgram('llvm-link')
        env['LLVM_OBJCOPY'] = mx_sulong.findBundledLLVMProgram('llvm-objcopy')
        env['GRAALVM_LLVM_HOME'] = mx_subst.path_substitutions.substitute("<path:SULONG_HOME>")
        return env


def collectExcludes(path):
    def _collect(path, skip=None):
        for root, _, files in os.walk(path):
            if skip and skip(os.path.relpath(root, path)):
                continue
            for f in files:
                if f.endswith('.exclude'):
                    for line in open(os.path.join(root, f)):
                        yield line.strip()
    # use `yield from` in python 3.3
    for x in _collect(path, lambda p: p.startswith('os_arch')):
        yield x

    os_arch_root = os.path.join(path, 'os_arch')
    if os.path.exists(os_arch_root):
        try:
            os_path = next(x for x in (os.path.join(os_arch_root, os_dir) for os_dir in [mx.get_os(), 'others']) if os.path.exists(x))
            os_arch_path = next(x for x in (os.path.join(os_path, arch_dir) for arch_dir in [mx.get_arch(), 'others']) if os.path.exists(x))
            # use `yield from` in python 3.3
            for x in _collect(os_arch_path):
                yield x
        except StopIteration:
            pass


class ExternalTestSuite(SulongTestSuite):  # pylint: disable=too-many-ancestors
    def __init__(self, *args, **kwargs):
        super(ExternalTestSuite, self).__init__(*args, **kwargs)
        if hasattr(self, 'testDir'):
            self.testDir = self.testDir.replace('/', os.sep)
        else:
            self.testDir = ''
        if hasattr(self, 'fileExts'):
            self.fileExts = self.fileExts if not isinstance(self.fileExts, _basestring) else [self.fileExts]
        else:
            self.fileExts = ['.c', '.cpp', '.C']
        if not hasattr(self, 'configDir'):
            self.configDir = 'configs'


    def defaultTestClasses(self):
        return ["com.oracle.truffle.llvm.tests.GCCSuite"]

    def getTestFile(self):
        if not hasattr(self, '_testfile'):
            self._testfile = os.path.join(self.getOutput(), 'test.include')
            with open(self._testfile, 'w') as f:
                mx.logv("Writing test file: " + self._testfile)
                # call getResults() ensure self.results is populated
                _ = self.getResults()
                f.write("\n".join(("default: " + r for r in self.results)))
        return self._testfile

    def _get_test_intern(self):
        ### Excludes
        def _maybe_pattern(name):
            for c in '*?[]!':
                if c in name:
                    return True
            return False

        exclude_patterns = []
        exclude_files = []

        # full name check is cheaper than pattern matching
        for exclude in collectExcludes(os.path.join(self.dir, "..", self.configDir)):
            if _maybe_pattern(exclude):
                exclude_patterns.append(exclude)
            else:
                exclude_files.append(exclude)

        def _match_pattern(name):
            for pattern in exclude_patterns:
                if fnmatch.fnmatch(name, pattern):
                    return True
            return False

        ## Testcase collection
        _tests = []
        root = self.get_test_source(resolve=True)
        for path, _, files in os.walk(os.path.join(root, self.testDir)):
            for f in files:
                absPath = os.path.join(path, f)
                relPath = os.path.relpath(absPath, root)
                _, ext = os.path.splitext(relPath)
                if ext in self.fileExts and relPath not in exclude_files and not _match_pattern(relPath):
                    _tests.append(relPath + ".dir")

        return _tests

    def get_test_source(self, resolve=False):
        roots = [d.get_path(resolve=resolve) for d in self.buildDependencies if d.isPackedResourceLibrary()]
        assert len(roots) == 1
        return roots[0]

    def getTests(self):
        if not hasattr(self, '_tests'):
            self._tests = self._get_test_intern()
        return self._tests

    def getBuildEnv(self, replaceVar=mx_subst.path_substitutions):
        env = super(ExternalTestSuite, self).getBuildEnv(replaceVar=replaceVar)
        roots = [d.get_path(resolve=True) for d in self.buildDependencies if d.isPackedResourceLibrary()]
        # we pass tests via TESTFILE to avoid "argument list too long" issue
        env['TESTS'] = ''
        env['VPATH'] = ':'.join(roots)
        env['TESTFILE'] = self.getTestFile()
        return env


class BootstrapToolchainLauncherProject(mx.Project):  # pylint: disable=too-many-ancestors
    def __init__(self, suite, name, deps, workingSets, theLicense, **kwArgs):
        super(BootstrapToolchainLauncherProject, self).__init__(suite, name, srcDirs=[], deps=deps, workingSets=workingSets, d=suite.dir, theLicense=theLicense, **kwArgs)

    def launchers(self):
        for tool in self.suite.toolchain._supported_tools():
            for exe in self.suite.toolchain._tool_to_aliases(tool):
                if mx.is_windows() and exe.endswith('.exe'):
                    exe = exe[:-4] + ".cmd"
                result = os.path.join(self.get_output_root(), exe)
                yield result, tool, exe

    def getArchivableResults(self, use_relpath=True, single=False):
        for result, _, exe in self.launchers():
            yield result, os.path.join('bin', exe)

    def getBuildTask(self, args):
        return BootstrapToolchainLauncherBuildTask(self, args, 1)

    def isPlatformDependent(self):
        return True

    def getJavaProperties(self, replaceVar=mx_subst.path_substitutions):
        ret = {}
        if hasattr(self, "javaProperties"):
            for key, value in self.javaProperties.items():
                ret[key] = replaceVar.substitute(value, dependency=self)
        return ret


def _quote_windows(arg):
    return '"{}"'.format(arg)


class BootstrapToolchainLauncherBuildTask(mx.BuildTask):
    def __str__(self):
        return "Generating " + self.subject.name

    def newestOutput(self):
        return mx.TimeStampFile.newest([result for result, _, _ in self.subject.launchers()])

    def needsBuild(self, newestInput):
        sup = super(BootstrapToolchainLauncherBuildTask, self).needsBuild(newestInput)
        if sup[0]:
            return sup

        for result, tool, exe in self.subject.launchers():
            if not os.path.exists(result):
                return True, result + ' does not exist'
            with open(result, "r") as f:
                on_disk = f.read()
            if on_disk != self.contents(tool, exe):
                return True, 'command line changed for ' + os.path.basename(result)

        return False, 'up to date'

    def build(self):
        mx.ensure_dir_exists(self.subject.get_output_root())
        for result, tool, exe in self.subject.launchers():
            with open(result, "w") as f:
                f.write(self.contents(tool, exe))
            os.chmod(result, 0o755)

    def clean(self, forBuild=False):
        if os.path.exists(self.subject.get_output_root()):
            mx.rmtree(self.subject.get_output_root())

    def contents(self, tool, exe):
        # platform support
        all_params = '"%*"' if mx.is_windows() else '"$@"'
        _quote = _quote_windows if mx.is_windows() else pipes.quote
        # build command line
        java = mx.get_jdk().java
        classpath_deps = [dep for dep in self.subject.buildDependencies if isinstance(dep, mx.ClasspathDependency)]
        extra_props = ['-Dorg.graalvm.launcher.executablename="{}"'.format(exe)]
        main_class = self.subject.suite.toolchain._tool_to_main(tool)
        # add jvm args from dependencies
        jvm_args = [_quote(arg) for arg in mx.get_runtime_jvm_args(classpath_deps)]
        # add properties from the project
        if hasattr(self.subject, "getJavaProperties"):
            for key, value in sorted(self.subject.getJavaProperties().items()):
                jvm_args.append("-D" + key + "=" + value)
        command = [java] + jvm_args + extra_props + [main_class, all_params]
        # create script
        if mx.is_windows():
            return "@echo off\n" + " ".join(command) + "\n"
        else:
            return "#!/usr/bin/env bash\n" + "exec " + " ".join(command) + "\n"


class CMakeSupport(object):
    @staticmethod
    def check_cmake():
        try:
            CMakeSupport.run_cmake(["--version"], silent=False, nonZeroIsFatal=False)
        except OSError as e:
            mx.abort(str(e) + "\nError executing 'cmake --version'. Are you sure 'cmake' is installed? ")

    @staticmethod
    def run_cmake(cmdline, silent, *args, **kwargs):
        log_error = kwargs.pop("log_error", False)
        if mx._opts.verbose:
            mx.run(["cmake"] + cmdline, *args, **kwargs)
        else:
            with open(os.devnull, 'w') as fnull:
                err = mx.OutputCapture() if silent else None
                try:
                    mx.run(["cmake"] + cmdline, out=fnull, err=err, *args, **kwargs)
                except:
                    if log_error and err and err.data:
                        mx.log_error(err.data)
                    raise


class CMakeBuildTaskMixin(object):

    def __init__(self, args, project, *otherargs, **kwargs):
        super(CMakeBuildTaskMixin, self).__init__(args, project, *otherargs, **kwargs)
        self._cmake_config_file = os.path.join(project.suite.get_mx_output_dir(), 'cmakeConfig',
                                               mx.get_os() + '-' + mx.get_arch() if project.isPlatformDependent() else '',
                                               type(project).__name__,
                                               project._extra_artifact_discriminant(),
                                               self.name)

    def _write_guard(self, source_dir, cmake_config):
        with mx.SafeFileCreation(self.guard_file()) as sfc:
            with open(sfc.tmpPath, 'w') as fp:
                fp.write(self._guard_data(source_dir, cmake_config))

    def _guard_data(self, source_dir, cmake_config):
        return source_dir + '\n' + '\n'.join(cmake_config)

    def _need_configure(self):
        source_dir = self.subject.source_dirs()[0]
        cmake_lists = os.path.join(source_dir, "CMakeLists.txt")
        guard_file = self.guard_file()
        cmake_config = self.subject.cmake_config()
        if not os.path.exists(guard_file):
            return True, "No CMake configuration found - reconfigure"
        if os.path.exists(cmake_lists) and mx.TimeStampFile(cmake_lists).isNewerThan(mx.TimeStampFile(guard_file)):
            return True, cmake_lists + " is newer than the configuration - reconfigure"
        with open(guard_file, 'r') as fp:
            if fp.read() != self._guard_data(source_dir, cmake_config):
                return True, "CMake configuration changed - reconfigure"
            return False, None

    def guard_file(self):
        return self._cmake_config_file


class CMakeBuildTask(CMakeBuildTaskMixin, mx.NativeBuildTask):

    def __str__(self):
        return 'Building {} with CMake'.format(self.subject.name)

    def _build_run_args(self):
        cmdline, cwd, env = super(CMakeBuildTask, self)._build_run_args()

        def _flatten(lst):
            for e in lst:
                if isinstance(e, list):
                    for sub in _flatten(e):
                        yield sub
                else:
                    yield e

        # flatten cmdline to support for multiple make targets
        return list(_flatten(cmdline)), cwd, env

    def build(self):
        # get cwd and env
        self._configure()
        # This is copied from the super call because we want to make it
        # less verbose but calling super does not allow us to do that.
        # super(CMakeBuildTask, self).build()
        cmdline, cwd, env = self._build_run_args()
        if mx._opts.verbose:
            mx.run(cmdline, cwd=cwd, env=env)
        else:
            with open(os.devnull, 'w') as fnull:
                mx.run(cmdline, cwd=cwd, env=env, out=fnull)
        self._newestOutput = None
        # END super(CMakeBuildTask, self).build()
        source_dir = self.subject.source_dirs()[0]
        self._write_guard(source_dir, self.subject.cmake_config())

    def needsBuild(self, newestInput):
        mx.logv('Checking whether to build {} with CMake'.format(self.subject.name))
        need_configure, reason = self._need_configure()
        return need_configure, "rebuild needed by CMake ({})".format(reason)

    def _need_configure(self):
        if not os.path.exists(os.path.join(self.subject.dir, 'Makefile')):
            return True, "No existing Makefile - reconfigure"
        return super(CMakeBuildTask, self)._need_configure()

    def _configure(self, silent=False):
        need_configure, _ = self._need_configure()
        if not need_configure:
            return
        _, cwd, env = self._build_run_args()
        source_dir = self.subject.source_dirs()[0]
        cmakefile = os.path.join(self.subject.dir, 'CMakeCache.txt')
        if os.path.exists(cmakefile):
            # remove cache file if it exist
            os.remove(cmakefile)
        cmdline = ["-G", "Unix Makefiles", source_dir] + self.subject.cmake_config()
        CMakeSupport.check_cmake()
        CMakeSupport.run_cmake(cmdline, silent=silent, cwd=cwd, env=env)
        return True


class AbstractSulongNativeProject(mx.NativeProject):  # pylint: disable=too-many-ancestors
    def __init__(self, suite, name, deps, workingSets, subDir, results=None, output=None, **args):
        projectDir = args.pop('dir', None)
        if projectDir:
            d_rel = projectDir
        elif subDir is None:
            d_rel = name
        else:
            d_rel = os.path.join(subDir, name)
        d = os.path.join(suite.dir, d_rel.replace('/', os.sep))
        srcDir = args.pop('sourceDir', d)
        if not srcDir:
            mx.abort("Exactly one 'sourceDir' is required")
        srcDir = mx_subst.path_substitutions.substitute(srcDir)
        super(AbstractSulongNativeProject, self).__init__(suite, name, subDir, [srcDir], deps, workingSets, results, output, d, **args)


class CMakeMixin(object):
    def __init__(self, *args, **kwargs):
        super(CMakeMixin, self).__init__(*args, **kwargs)
        cmake_config = kwargs.pop('cmakeConfig', {})
        self.cmake_config = lambda: ['-D{}={}'.format(k, mx_subst.path_substitutions.substitute(v).replace('{{}}', '$')) for k, v in sorted(cmake_config.items())]


class CMakeProject(CMakeMixin, AbstractSulongNativeProject):  # pylint: disable=too-many-ancestors
    def __init__(self, suite, name, deps, workingSets, subDir, results=None, output=None, **args):
        super(CMakeProject, self).__init__(suite, name, deps, workingSets, subDir, results=results, output=output, **args)
        self.dir = self.getOutput()

    def getBuildTask(self, args):
        return CMakeBuildTask(args, self)


class CMakeNinjaBuildTask(CMakeBuildTaskMixin, mx_native.NinjaBuildTask):

    def needsBuild(self, newestInput):
        mx.logv('Checking whether to reconfigure {} with CMake'.format(self.subject.name))
        need_configure, reason = self._need_configure()
        if need_configure:
            return need_configure, "reconfigure needed by CMake ({})".format(reason)
        return super(CMakeNinjaBuildTask, self).needsBuild(newestInput)

    def build(self):
        super(CMakeNinjaBuildTask, self).build()
        # write guard file
        source_dir = self.subject.source_dirs()[0]
        self._write_guard(source_dir, self.subject.cmake_config())
        # call install targets
        if self.subject._install_targets:
            self.ninja._run(*self.subject._install_targets)

    def newestOutput(self):
        return mx.TimeStampFile.newest([_path for _path, _ in self.subject.getArchivableResults()])


class CMakeNinjaProject(CMakeMixin, mx_native.NinjaProject):  # pylint: disable=too-many-ancestors
    """A CMake project that is built using Ninja.

    Attributes
        ninja_targets: list of str, optional
            Targets that should be built using Ninja
        ninja_install_targets: list of str, optional
            Targets that executed after a successful build. In contrast to `ninja_targets`, the `ninja_install_targets`
            are not considered when deciding whether a project needs to be rebuilt. This is needed because `install`
            targets created by CMake are often executed unconditionally, which would cause the project to be always
            rebuilt.
    """
    def __init__(self, suite, name, deps, workingSets, subDir, ninja_targets=None, ninja_install_targets=None, results=None, output=None, **args):
        projectDir = args.pop('dir', None)
        if projectDir:
            d_rel = projectDir
        elif subDir is None:
            d_rel = name
        else:
            d_rel = os.path.join(subDir, name)
        d = os.path.join(suite.dir, d_rel.replace('/', os.sep))
        srcDir = args.pop('sourceDir', d)
        if not srcDir:
            mx.abort("Exactly one 'sourceDir' is required")
        srcDir = mx_subst.path_substitutions.substitute(srcDir)
        self._install_targets = [mx_subst.path_substitutions.substitute(x) for x in ninja_install_targets or []]
        self._ninja_targets = [mx_subst.path_substitutions.substitute(x) for x in ninja_targets or []]
        super(CMakeNinjaProject, self).__init__(suite, name, subDir, [srcDir], deps, workingSets, d, results=results, output=output, **args)
        # self.dir = self.getOutput()

    def generate_manifest(self, path):
        source_dir = self.source_dirs()[0]
        out_dir = os.path.dirname(path)
        cmakefile = os.path.join(out_dir, 'CMakeCache.txt')
        if os.path.exists(cmakefile):
            # remove cache file if it exist
            os.remove(cmakefile)
        cmake_config = self.cmake_config()

        # explicitly set ninja executable if not on path
        cmake_make_program = 'CMAKE_MAKE_PROGRAM'
        if cmake_make_program not in cmake_config and mx_native.Ninja.binary != 'ninja':
            cmake_config.append('-D{}={}'.format(cmake_make_program, mx_native.Ninja.binary))

        # cmake will always create build.ninja - there is nothing we can do about it ATM
        cmdline = ["-G", "Ninja", source_dir] + cmake_config
        CMakeSupport.check_cmake()
        CMakeSupport.run_cmake(cmdline, silent=True, cwd=out_dir, log_error=True)
        # move the build.ninja to the temporary path (just move it back later ... *sigh*)
        shutil.copyfile(os.path.join(out_dir, mx_native.Ninja.default_manifest), path)
        return True

    def _build_task(self, target_arch, args):
        return CMakeNinjaBuildTask(args, self, target_arch, self._ninja_targets)

    def getResults(self, replaceVar=mx_subst.results_substitutions):
        return [mx_subst.as_engine(replaceVar).substitute(rt, dependency=self) for rt in self.results]

    def _archivable_results(self, target_arch, use_relpath, single):
        def result(base_dir, file_path):
            assert not mx.isabs(file_path)
            archive_path = file_path if use_relpath else mx.basename(file_path)
            return mx.join(base_dir, file_path), archive_path

        out_dir_arch = mx.join(self.out_dir, target_arch)
        for _result in self.getResults():
            yield result(out_dir_arch, _result)


class DocumentationBuildTask(mx.AbstractNativeBuildTask):
    def __str__(self):
        return 'Building {} with Documentation Build Task'.format(self.subject.name)

    def build(self):
        pass

    def needsBuild(self, newestInput):
        return False, None

    def clean(self, forBuild=False):
        pass


class DocumentationProject(AbstractSulongNativeProject):  # pylint: disable=too-many-ancestors
    def __init__(self, suite, name, deps, workingSets, subDir, results=None, output=None, **args):
        super(DocumentationProject, self).__init__(suite, name, deps, workingSets, subDir, results, output, **args)
        self.dir = self.source_dirs()[0]

    def getBuildTask(self, args):
        return DocumentationBuildTask(args, self)


class HeaderBuildTask(mx.NativeBuildTask):
    def __str__(self):
        return 'Building {} with Header Build Task'.format(self.subject.name)

    def build(self):
        self._newestOutput = None

    def needsBuild(self, newestInput):
        return (False, "up to date according to GNU Make")

    def clean(self, forBuild=False):
        pass


class HeaderProject(AbstractSulongNativeProject):  # pylint: disable=too-many-ancestors
    def __init__(self, suite, name, deps, workingSets, subDir, results=None, output=None, **args):
        super(HeaderProject, self).__init__(suite, name, deps, workingSets, subDir, results, output, **args)
        self.dir = self.source_dirs()[0]

    def getBuildTask(self, args):
        return HeaderBuildTask(args, self)

    def isPlatformDependent(self):
        return False
