# Copyright 2022 Google LLC
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Main gRPC server to execute Python Tsunami plugins."""

from concurrent import futures
import importlib
import pkgutil
import signal
import threading
import types

from absl import app
from absl import flags
from absl import logging
import grpc
from grpc_health.v1 import health
from grpc_health.v1 import health_pb2
from grpc_health.v1 import health_pb2_grpc
from grpc_reflection.v1alpha import reflection

import plugin_service
import tsunami_plugin
from common.net.http.requests_http_client import RequestsHttpClientBuilder
from plugin.payload.payload_generator import PayloadGenerator
from plugin.payload.payload_secret_generator import PayloadSecretGenerator
from plugin.payload.payload_utility import get_parsed_payload
from plugin.tcs_client import TcsClient
import plugin_service_pb2
import plugin_service_pb2_grpc


_HOST = '127.0.0.1'
_PORT = flags.DEFINE_integer('port', 34567, 'port to listen on.')
_THREADS = flags.DEFINE_integer('threads', 10,
                                'number of worker threads in thread pool.')
_OUTPUT = flags.DEFINE_string('log_output', '/tmp',
                              'server execution log directory.')
_TIMEOUT_SEC = flags.DEFINE_float(
    'timeout_seconds', 10, 'Timeout in seconds for complete HTTP calls.'
)
_LOG_ID = flags.DEFINE_string('log_id', '',
                              'id to track logs for all outgoing HTTP calls.')
_TRUST_ALL_SSL_CERT = flags.DEFINE_boolean(
    'trust_all_ssl_cert', True, 'Trust all SSL certificates on HTTPS traffic.'
)
_CALLBACK_ADDRESS = flags.DEFINE_string(
    'callback_address',
    '127.0.0.1',
    'Hostname or IP address of the callback server.',
)
_CALLBACK_PORT = flags.DEFINE_integer(
    'callback_port', 8881, 'Callback server port for HTTP logging service.'
)
_CALLBACK_POLLING_URI = flags.DEFINE_string(
    'polling_uri',
    'http://127.0.0.1:8880',
    'Callback server URI for log polling service.',
)


def main(unused_argv):
  _configure_log()

  # Load plugins from tsunami_plugins repository.
  plugin_pkg = importlib.import_module(
      'py_plugins'
  )
  _import_py_plugins(plugin_pkg)

  server_addr = f'{_HOST}:{_PORT.value}'
  server = grpc.server(futures.ThreadPoolExecutor(max_workers=_THREADS.value))

  _configure_plugin_service(server)
  health_servicer = _register_health_service(server)
  server.add_insecure_port(server_addr)

  server.start()
  logging.info('Server started at %s.', server_addr)
  # Set to SERVING after server proves its availability.
  _set_health_service_to_serving(server, health_servicer)

  # Java Process.destroy() sends SIGTERM
  sig_term_received = threading.Event()

  def on_sigterm(signum, frame):
    logging.info('Got signal %s, %s', signum, frame)
    sig_term_received.set()

  signal.signal(signal.SIGTERM, on_sigterm)
  sig_term_received.wait()
  logging.info('Stopped RPC server, Waiting for RPCs to complete...')
  server.stop(3).wait()
  logging.info('Done stopping server')


def _import_py_plugins(plugin_pkg: types.ModuleType):
  """Imports all Python Tsunami plugin modules."""
  for _, name, is_pkg in pkgutil.walk_packages(plugin_pkg.__path__):
    full_name = plugin_pkg.__name__ + '.' + name
    pkg = importlib.import_module(full_name)
    if is_pkg:
      _import_py_plugins(pkg)
    else:
      logging.info('Loaded plugin module %s', full_name)


def _configure_log():
  """Store and print out log stream."""
  logging.use_absl_handler()
  logger = logging.get_absl_handler()
  logger.use_absl_log_file(
      'py_plugin_server', _OUTPUT.value
  )
  # Label the log record that comes from the python server for debugging.
  logger.setFormatter(
      logging.PythonFormatter('[Python Server] %(asctime)s %(message)s')
  )
  logging.set_verbosity(logging.INFO)


def _configure_plugin_service(server):
  """Configures the main plugin service for handling plugin related gRPC requests."""
  http_client = (
      RequestsHttpClientBuilder()
      .set_timeout_sec(_TIMEOUT_SEC.value)
      .set_verify_ssl(not _TRUST_ALL_SSL_CERT.value)
      .set_log_id(_LOG_ID.value)
      .build()
  )
  callback_client = TcsClient(
      _CALLBACK_ADDRESS.value,
      _CALLBACK_PORT.value,
      _CALLBACK_POLLING_URI.value,
      http_client,
  )
  payload_generator = PayloadGenerator(
      PayloadSecretGenerator(), get_parsed_payload(), callback_client
  )
  # Get all VulnDetector class implementations.
  plugins = [
      cls(http_client, payload_generator)
      for cls in tsunami_plugin.VulnDetector.__subclasses__()
  ]
  logging.info('Configured %d python plugin:', len(plugins))
  for plugin in plugins:
    logging.info('\t%s', plugin.GetPluginDefinition().info.name)
  servicer = plugin_service.PluginServiceServicer(
      py_plugins=plugins, max_workers=_THREADS.value
  )
  plugin_service_pb2_grpc.add_PluginServiceServicer_to_server(servicer, server)


def _register_health_service(server):
  """Add gRPC health checking service to server."""
  health_servicer = health.HealthServicer(
      experimental_non_blocking=True,
      experimental_thread_pool=futures.ThreadPoolExecutor(
          max_workers=_THREADS.value))
  health_pb2_grpc.add_HealthServicer_to_server(health_servicer, server)
  return health_servicer


def _set_health_service_to_serving(server, health_servicer):
  """Set gRPC health checking service to SERVING."""
  # Set all services to SERVING.
  services = tuple(service.full_name
                   for service in plugin_service_pb2.DESCRIPTOR.services_by_name
                   .values()) + (reflection.SERVICE_NAME, health.SERVICE_NAME)
  for service in services:
    health_servicer.set(service, health_pb2.HealthCheckResponse.SERVING)
    logging.info('Service %s is now SERVING', service)
  reflection.enable_server_reflection(services, server)


if __name__ == '__main__':
  flags.set_default(logging.ALSOLOGTOSTDERR, True)
  app.run(main)
