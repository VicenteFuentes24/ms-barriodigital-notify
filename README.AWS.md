# Notify en AWS

Se mantiene Java 21 / Spring Boot 4.1.1. Proyecto Maven: `notify/`.
No se modifican consumidores, ACK/NACK manual, reintentos, DLQ, idempotencia,
contrato RequestEvent, PDFBox, tickets JSON ni correo de pruebas.

Variables RabbitMQ: `RABBITMQ_ADDRESSES` (dos IP privadas con puerto),
`RABBITMQ_USERNAME`, `RABBITMQ_PASSWORD`, `RABBITMQ_VIRTUAL_HOST=barriodigital`.
Con lista vacia siguen funcionando `RABBITMQ_HOST=localhost` y `RABBITMQ_PORT=5672`.
La recuperacion/reconexion existente de Spring AMQP y del listener se conserva;
no se agrega otro mecanismo de retry que multiplique intentos del consumidor.
La topologia sigue siendo propiedad exclusiva de infra.

Compose AWS central: `infra-barriodigital/aws/compose.apps.yml`.
No publica 8087. Monta el volumen configurable `NOTIFY_VOLUME_NAME` en `/app/data`
y fija `NOTIFY_STORAGE_DIR=/app/data` para PDF, tickets y ledger de idempotencia.
No cambiar el nombre del volumen al actualizar, no usar down -v y respaldarlo.
Se conserva una unica instancia consumidora: el ledger de archivos no es una
coordinacion distribuida entre multiples replicas.

SMTP usa Mailpit de la red Docker: `SMTP_HOST=mailpit`, `SMTP_PORT=1025`,
`SMTP_AUTH=false`, `SMTP_SSL=false`, `SMTP_STARTTLS=false`, `SMTP_FROM` configurable.
Mailpit conserva mensajes en su volumen y su UI solo se enlaza a loopback.
No envia correos a Internet. No se integra otro proveedor.
`NOTIFY_MAX_ATTEMPTS` y `NOTIFY_RETRY_DELAY_MS` mantienen sus valores anteriores.

El Dockerfile incorpora healthcheck HTTP de Java, sin depender de curl/wget ni
modificar la aplicacion. Consulta `/actuator/health`; no sustituye una prueba real
de entrega SMTP. Docker no reinicia automaticamente un proceso solo por unhealthy.
Se conserva restart unless-stopped para salidas del proceso.

```powershell
cd notify
.\mvnw.cmd -B -ntp test
.\mvnw.cmd -B -ntp clean package
```

La prueba opt-in `NotifyRabbitIT` utiliza un vhost temporal y necesita un broker
y Mailpit reales; revisar el README existente para ejecutarla. No fue ejecutada
contra AWS durante esta adaptacion. Persiste una ventana de duplicacion SMTP si
el proceso cae despues del envio y antes de guardar el resultado en el ledger.
Guia de despliegue y tuneles: README.AWS.md de infra-barriodigital.
