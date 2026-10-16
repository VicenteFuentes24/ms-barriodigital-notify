# ms-barriodigital-notify

Consumidor de comandos de BarrioDigital EP3/EP4. Conserva Java 21, Spring Boot
4.1.1, Maven y el package `cl.barriodigital.notify` del proyecto recibido.
No agrega base de datos, JPA, Kafka, login ni endpoints de negocio.

```text
Requests -> RabbitMQ (topologia de Infra) -> Notify
                                         -> SMTP (Mailpit local)
                                         -> ticket JSON verificable
                                         -> constancia PDF real
```

## Contrato y topologia

Notify NO declara Queue, Exchange, Binding, RabbitAdmin ni politicas.
`spring.rabbitmq.dynamic=false`. La unica fuente de topologia sigue siendo
`infra-barriodigital/rabbitmq/definitions.json`; hay que iniciar Infra primero.
Vhost habitual: `barriodigital`. Funciona con `cmd.direct` y `cmd.topic`.

| Cola | Comando | Evento esperado | Salida |
| --- | --- | --- | --- |
| q.cmd.email | email.send | REQUEST_STATUS_CHANGED, ADMITIDO o RESUELTO | SMTP |
| q.cmd.crew | crew.ticket | REQUEST_STATUS_CHANGED, EN_TERRENO | Ticket JSON |
| q.cmd.certificate | certificate.gen | REQUEST_CREATED/INGRESADO o REQUEST_STATUS_CHANGED/RESUELTO | PDF |

Las politicas de Infra envian los mensajes rechazados a `cmd.dead.dlx`, usando
`q.cmd.email.dlq`, `q.cmd.crew.dlq` y `q.cmd.certificate.dlq` como routing keys.
No se agregan argumentos DLX, colas de retry ni consumidores de DLQ.

El DTO reproduce RequestEvent de Requests: `type`, `eventId` (UUID), `timestamp`
(ISO-8601), `traceId`, `correlationId`, `requestId`, `procedureTypeId`, `status`,
`createdBy`. JSON obligatorio, hasta 64 KiB, profundidad limitada, validacion
Jakarta y combinacion comando/tipo/estado. No se confia en `__TypeId__`.
Se toleran campos adicionales para evolucion compatible del contrato.

El destinatario de correo es exclusivamente `createdBy`. Si Requests entrego
un oid, null o correo invalido, `email.send` va a DLQ; no se inventa una direccion.
Ticket/PDF no requieren correo y no lo incluyen, minimizando datos personales.

## Configuracion

`.env.example` no contiene credenciales reales. Spring Boot NO carga `.env`
automaticamente: exportar variables en PowerShell, o usar Compose `--env-file`.
`.env`, `.env.*`, `data/`, `target/` y logs estan ignorados; `.env.example` no.

| Variable | Default / uso |
| --- | --- |
| SERVER_PORT | 8087 (configurable) |
| RABBITMQ_HOST / PORT | localhost / 5672; Docker usa rabbitmq / 5672 |
| RABBITMQ_USERNAME / PASSWORD | Requeridos; no hay credenciales embebidas |
| RABBITMQ_VIRTUAL_HOST | barriodigital |
| BARRIODIGITAL_NETWORK_NAME | infra-barriodigital_barriodigital-net; Compose externo |
| SMTP_HOST / PORT | localhost / 1025; Docker local usa mailpit / 1025 |
| SMTP_USERNAME / PASSWORD | Vacios con Mailpit; configurar para SMTP autentico |
| SMTP_FROM | no-reply@barriodigital.test |
| SMTP_AUTH / STARTTLS / SSL | false para Mailpit; ajustar para SMTP real |
| MAILPIT_UI_PORT | 8025, solo loopback |
| NOTIFY_STORAGE_DIR | ./data; /app/data en Docker |
| NOTIFY_MAX_ATTEMPTS | 3 intentos totales, permitido 1..10 |
| NOTIFY_RETRY_DELAY_MS | 1000, permitido 0..30000 |
| NOTIFY_LISTENERS_AUTO_STARTUP | true; false solo para pruebas sin broker |

SMTP tiene timeouts de conexion, lectura y escritura de 5 segundos. Para SMTP
externo usar TLS/autenticacion segun el proveedor, no publicar secretos en Git.
Mailpit recibe correo SMTP real de prueba, pero NO lo entrega a Internet.

## Ejecutar localmente

Requisitos: JDK 21, Docker Desktop Linux operativo, RabbitMQ de Infra healthy,
topologia importada y credenciales locales del vhost. No usar credenciales Azure
ni Supabase. En otra consola mantener Requests funcionando como antes.

```powershell
cd 'G:\Desarrollo EP3-EP4\ms-barriodigital-notify\notify'
$cred = Get-Credential -Message 'Usuario local de RabbitMQ'
$env:RABBITMQ_USERNAME = $cred.UserName
$env:RABBITMQ_PASSWORD = $cred.GetNetworkCredential().Password
$env:RABBITMQ_HOST = 'localhost'
$env:RABBITMQ_PORT = '5672'
$env:RABBITMQ_VIRTUAL_HOST = 'barriodigital'

# Solo Mailpit; no recrea RabbitMQ ni toca el Compose anterior.
docker compose --env-file .env.example -f compose.local.yml up -d mailpit
$env:SMTP_HOST = '127.0.0.1'
$env:SMTP_PORT = '1025'
$env:NOTIFY_STORAGE_DIR = './data'
.\mvnw.cmd spring-boot:run
```

Management RabbitMQ: http://localhost:15672/ (seleccionar vhost barriodigital).
Mailpit: http://localhost:8025/ (sin login, exclusivamente desarrollo local).
Health: `Invoke-RestMethod http://localhost:8087/actuator/health`.
Es el unico endpoint Actuator expuesto, sin detalles sensibles; Rabbit/SMTP caidos
pueden producir DOWN/503. No hay API publica de descarga de documentos ni PII.

Para contenedores, con las mismas variables Rabbit exportadas:

```powershell
docker compose --env-file .env.example -f compose.local.yml --profile app up -d --build
docker compose --env-file .env.example -f compose.local.yml logs -f notify
docker compose --env-file .env.example -f compose.local.yml cp notify:/app/data ./notify-export
```

No ejecutar simultaneamente Notify en Java y Docker para una demostracion simple.
El Compose agrega solo Notify/Mailpit a la red externa ya existente. RabbitMQ no
es una dependencia declarada aqui: iniciarlo con Infra. Si cambia el nombre de
su red, exportar `BARRIODIGITAL_NETWORK_NAME`. El contenedor Java usa un usuario
no root y umask 0077. Conservar el volumen `notify-data`: **no usar down -v**
si se necesita conservar idempotencia/documentos. No exponer puertos a Internet.

## ACK, NACK, intentos e idempotencia

Tres listeners con MANUAL, prefetch=1 y un consumidor por cola.

1. Validar JSON y semantica. Payload invalido: `basicNack(tag, false, false)`.
2. Bloquear la clave UUID + comando y contrastar fingerprint SHA-256 del DTO.
3. Si ya esta completada: ACK sin repetir SMTP ni generar archivos.
4. Guardar el contador de intento ANTES de actuar. Reintentar fallos temporales
   localmente, con espera acotada y el mensaje aun sin ACK.
5. Tras completar la accion, persistir recibo COMPLETED; despues `basicAck`.
6. Agotar el limite: `basicNack(tag, false, false)`; DLQ mediante politica Infra.
7. Interrupcion al detenerse: NACK con requeue=true; el contador persiste, no
   reinicia el limite. Fallo de almacen/corrupcion: NACK sin requeue, no ACK falso.

No hay requeue infinito para errores de negocio ni republicacion sin confirmacion.
Un fallo enviando ACK se propaga al contenedor; una reentrega encuentra el recibo
completado y no repite la accion. Un mismo evento RESUELTO tiene DOS claves,
`eventId.email` y `eventId.certificate`, por lo que ambos comandos se ejecutan.
El mismo eventId/comando con payload diferente se rechaza, no se acepta como duplicado.

Archivos bajo storage:

- `receipts/<uuid>.<command>.json`: fingerprint, attempts, completed, updatedAt.
- `receipts/<uuid>.<command>.lock`: coordinacion de acceso; no borrar en caliente.
- `tickets/<uuid>.crew.json`: ticketId, evento, tramite, procedimiento, estado,
  timestamp, traceId y correlationId. No simula despacho/asignacion de cuadrilla.
- `certificates/<uuid>.certificate.pdf`: constancia academica de ingreso/resolucion;
  contiene IDs, estado y trazabilidad. No tiene firma ni validez legal declarada.

Escritura temporal, fsync de archivo y rename atomico en el mismo directorio.
PDFBox 3.0.8 genera un PDF real con Liberation Sans embebida, sin depender de
fuentes de Windows. Glifos no soportados por esa fuente se representan como `?`.
Los archivos existentes se verifican antes de dar la accion por completada.
Los logs muestran comando, eventId y resultado/motivo, no payload, correo ni secretos.
Contadores Micrometer: notify.command.received/completed/duplicate/retries/rejected;
no se exponen endpoints de metrics a Internet.

## Pruebas

Sin Microsoft, Supabase, RabbitMQ ni SMTP reales:

```powershell
.\mvnw.cmd test
.\mvnw.cmd clean package
```

Prueba opt-in completa (requiere administrador de RabbitMQ Management local):

```powershell
cd 'G:\Desarrollo EP3-EP4\ms-barriodigital-notify\notify'
$cred = Get-Credential -Message 'Administrador local de RabbitMQ'
.\Test-Notify.ps1 -Credential $cred
```

El script inicia un Mailpit temporal con puertos libres, ejecuta
`.\mvnw.cmd '-Dtest=NotifyRabbitIT' test` con NOTIFY_INTEGRATION_TEST=true y
restaura todas las variables. El test crea un vhost `notify-it-<uuid>` e importa
la definicion existente de Infra cambiando SOLO el vhost en memoria; no copia
ni cambia la fuente de topologia. Al finalizar elimina solo ese vhost y su
Mailpit temporal; no consume ni purga colas de barriodigital. Si se mata el
proceso abruptamente pueden quedar recursos temporales para limpiar manualmente.

Demostraciones automatizadas de integracion:

- Direct/topic -> SMTP recibido, ticket JSON y PDF legible; ACK y colas vacias.
- Reentregas duplicadas -> ninguna accion repetida; RESUELTO permite correo + PDF.
- JSON invalido -> las tres DLQ con `x-death.reason=rejected`, comprobado con AMQP.
- Puerto SMTP cerrado en test -> exactamente 3 intentos persistidos y NACK a DLQ.
- Reinicio del contexto -> idempotencia persiste, /actuator/health responde UP.

Solo para ejecutar IT manualmente si YA hay Mailpit de prueba:

```powershell
$env:NOTIFY_INTEGRATION_TEST = 'true'
$env:RABBITMQ_MANAGEMENT_URL = 'http://localhost:15672'
$env:SMTP_HOST = '127.0.0.1'
$env:SMTP_PORT = '1025'
$env:NOTIFY_TEST_MAILPIT_URL = 'http://localhost:8025'
try { .\mvnw.cmd '-Dtest=NotifyRabbitIT' test }
finally { Remove-Item Env:NOTIFY_INTEGRATION_TEST -ErrorAction SilentlyContinue }
```

Para demostrar en la evaluacion con Requests: crear un tramite real -> PDF;
ADMITIDO -> Mailpit; EN_TERRENO -> ticket; RESUELTO -> correo + otro PDF.
Ver `Get-ChildItem .\data -Recurse`, leer ticket con `Get-Content`, abrir PDF
y correlacionar eventId con los logs ACK. No hay que insertar registros ficticios
en Supabase. Para NACK manual publicar JSON `{}` desde Management en cmd.direct,
routing key email.send, content_type application/json: debe ir a q.cmd.email.dlq.
Esa demostracion manual modifica colas reales: preferir el script aislado.

## Verificacion realizada (2026-10-13)

| Comprobacion ejecutada | Resultado |
| --- | --- |
| mvnw.cmd test | 37 tests, 0 fallos/errores |
| mvnw.cmd clean package | BUILD SUCCESS, 37 tests, JAR ejecutable |
| Test-Notify.ps1 / NotifyRabbitIT | 4 tests reales, 0 fallos/errores/omitidos |
| Docker build barriodigital-notify:local | Imagen construida correctamente |
| Compose --profile app config --quiet | Configuracion valida |
| Smoke Docker aislado | Health UP, uid=999, 3 ACK, 1 correo SMTP, ticket y PDF en volumen |
| Inspeccion del PDF renderizado | Texto legible, sin desbordamientos |
| Hash definitions.json y compose.yml de Infra | Sin cambios respecto al estado inicial |

Total: **41 tests aprobados**, ejecutados en suite normal + opt-in separadamente.
Los reportes estan en `target/surefire-reports`; el ejemplo PDF/PNG generado por
tests esta en `target/verification`. Los recursos Rabbit/SMTP/volumen usados para
las pruebas reales fueron temporales y se eliminaron al terminar. No quedo Notify
consumiendo colas de produccion: iniciarlo con las instrucciones anteriores.

Durante un cierre/reinicio rapido del contexto de integracion se observo un aviso
del collector de metricas del cliente Rabbit al cerrar un canal; no fallo ningun
test ni se alteraron ACK, DLQ o recibos. Queda para seguimiento de dependencias.
La advertencia CDS del JDK con el agente Mockito es de pruebas, no de produccion.
No se probaron SMTP externo, entrega a Internet, replicas distribuidas, caida de
energia, certificados firmados ni el flujo HTTP completo con Supabase.

## Limites y operacion

- Idempotencia durable LOCAL, para una instancia con volumen persistente. No es
  un almacen transaccional distribuido ni asegura consistencia en NFS/varias replicas.
- SMTP no ofrece exactamente-una-vez: una caida despues de aceptacion SMTP pero
  antes de guardar COMPLETED puede duplicar un correo. Message-ID estable ayuda
  a rastrear, pero no obliga al servidor a deduplicar. Tickets/PDF tienen nombres
  deterministas y verificacion que reduce esa ventana.
- Ni AFTER_COMMIT de Requests ni este consumidor eliminan la ventana entre commit
  de Requests y publicacion. No se agrego Outbox ni se modifico Supabase.
- DLX de colas classic y persistencia local no garantizan entrega ante todas las
  fallas del broker/energia. Requieren backups, supervision y mantenimiento.
- No hay consumo/replay automatico de DLQ. Investigar el motivo antes de reencolar.
  El limite agotado se conserva: devolver el mismo mensaje sin revisar el recibo
  lo enviara otra vez a DLQ. Restablecer intentos solo sobre recibos NO completados,
  con Notify detenido, respaldo y revision de posibles efectos SMTP ya ejecutados.
  No borrar recibos completados ni cambiar eventId para esquivar idempotencia.
- No hay retencion automatica: planificar limpieza segura de documentos/recibos
  despues del horizonte de reentrega, ACL/backups y volumen dimensionado.
- SMTP externo, despacho real de cuadrilla y certificados firmados son integraciones
  posteriores; las salidas actuales son correo de pruebas, ticket y constancia reales.

## Archivos

Modificados: pom.xml (PDFBox + agente Mockito en tests), .gitignore,
src/main/resources/application.properties y NotifyApplicationTests.

Nuevos: config/{NotifyProperties,RabbitConfig}; event/{RequestEvent,Command,
InvalidCommandException,EventDecoder}; messaging/{CommandListeners,CommandProcessor};
storage/{AtomicFiles,CommandStore}; service/{EmailService,CrewTicketService,
CertificateService}; tests TestEvents/EventDecoderTest/CommandStoreTest/
CommandProcessorTest/OutputServicesTest/NotifyRabbitIT; Dockerfile, .dockerignore,
compose.local.yml, .env.example, Test-Notify.ps1 y README.md.

No se modificaron frontend, BFF, Catalog, Requests, Supabase ni archivos de Infra.
No se hizo git push ni despliegue AWS.

Referencias: [ACK manual Spring AMQP](https://docs.spring.io/spring-amqp/reference/4.0/amqp/containerAttributes.html),
[Mailpit Docker](https://mailpit.axllent.org/docs/install/docker/),
[PDFBox](https://pdfbox.apache.org/download.cgi).
