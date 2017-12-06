#!/usr/bin/env python
from __future__ import print_function
import logging
import os
import sys
import uuid
import zipfile
import errno

from buck_logging import setup_logging
from buck_tool import ExecuteTarget, install_signal_handlers, \
    BuckStatusReporter
from buck_project import BuckProject, NoBuckConfigFoundException
from tracing import Tracing
from subprocutils import propagate_failure

THIS_DIR = os.path.dirname(os.path.realpath(__file__))


def main(argv, reporter):
    def get_repo(p):
        # Try to detect if we're running a PEX by checking if we were invoked
        # via a zip file.
        if zipfile.is_zipfile(argv[0]):
            from buck_package import BuckPackage
            return BuckPackage(p, reporter)
        else:
            from buck_repo import BuckRepo
            return BuckRepo(THIS_DIR, p, reporter)

    install_signal_handlers()
    try:
        tracing_dir = None
        build_id = str(uuid.uuid4())
        reporter.build_id = build_id
        with Tracing("main"):
            with BuckProject.from_current_dir() as project:
                tracing_dir = os.path.join(project.get_buck_out_log_dir(),
                                           'traces')
                with get_repo(project) as buck_repo:
                    # If 'kill' is the second argument, shut down the buckd
                    # process
                    if sys.argv[1:] == ['kill']:
                        buck_repo.kill_buckd()
                        return 0
                    return buck_repo.launch_buck(build_id)
    finally:
        if tracing_dir:
            Tracing.write_to_dir(tracing_dir, build_id)


if __name__ == "__main__":
    exit_code = 0
    reporter = BuckStatusReporter(sys.argv)
    fn_exec = None
    exception = None
    try:
        setup_logging()
        exit_code = main(sys.argv, reporter)
    except ExecuteTarget as e:
        # this is raised once 'buck run' has the binary
        # it can get here only if exit_code of corresponding buck build is 0
        fn_exec = e.execve
    except NoBuckConfigFoundException as e:
        exception = e
        # buck is started outside project root
        exit_code = 3  # COMMANDLINE_ERROR
    except IOError as e:
        exception = e
        if e.errno == errno.ENOSPC:
            exit_code = 14  # FATAL_DISK_FULL
        elif e.errno == errno.EPIPE:
            exit_code = 141  # SIGNAL_PIPE
        else:
            exit_code = 13  # FATAL_IO
    except KeyboardInterrupt:
        reporter.status_message = 'Python wrapper keyboard interrupt'
        exit_code = 130  # SIGNAL_INTERRUPT
    except Exception as e:
        exception = e
        # 11 is fatal bootstrapper error
        exit_code = 11

    if exception is not None:
        logging.error(str(exception))
        if reporter.status_message is None:
            reporter.status_message = str(exception)

    # report result of Buck call
    try:
        reporter.report(exit_code)
    except Exception as e:
        logging.debug(str(e))

    # execute 'buck run' target
    if fn_exec is not None:
        fn_exec()

    propagate_failure(exit_code)
