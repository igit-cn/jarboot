## 1.0.8-SNAPSHOT (7.28, 2021)

* refactor command protocol (重构命令执行协议)
* stdout default on, and change from session to broadcast (stdout命令默认开启，改为广播级，将广播到所有客户端)

## 1.0.7 (7.25, 2021)

* [#13] Command line parse error when space in quotation
* Rename module name from jarboot-service to jarboot-server
* Show the current version when start
* Fix priority sorted error
* Refactor modify some url api, service boot properties
* fastjson -> jackson
* code format add p3c, sonar, dependency and findbugs plugins
* Global config move in jarboot.properties
#### FEATURES:
* Support jar file, working directory and jdk path using absolute path or relative path.
* jarboot.properties add jarboot.services.enable-auto-start-after-start config.
* Server vm option from a file, and can edit in ui.
* Server start main arguments edit ui.
* Architecture optimization to support more concurrent terminal messages.
* Add startup.sh startup.cmd shutdown.sh shutdown.cmd file to start or shutdown jarboot server.
* Services sorted by name.
* Remove swagger-ui

## 1.0.6 (7.11, 2021)

#### FEATURES:

* User manager and permission control.