# Chat Virtual

Aplicación web para Apache Tomcat 11 y Java 17 o posterior. Incluye chat privado, presencia, archivos binarios por fragmentos con confirmación, salas de llamadas grupales en malla WebRTC y una interfaz adaptable a móvil.

## Abrir en NetBeans y crear el WAR

Abre este proyecto desde `pom.xml` como proyecto Maven. Maven descargará Gson y la API Jakarta WebSocket; el contenedor Tomcat proporciona la implementación WebSocket en ejecución. Si conservas la configuración Web Ant del proyecto en NetBeans, el primer **Clean and Build** descarga Gson desde Maven Central y lo añade al classpath y al WAR.

```sh
mvn clean package
```

El archivo resultante es `target/Chat-Virtual.war`. Despliega ese WAR en Tomcat 11; se requiere Java 17 o posterior. El context path local será `/Chat-Virtual` si se despliega con ese nombre. El endpoint del socket queda en `/Chat-Virtual/chat`.

En la pantalla inicial se indica IP o dominio, puerto y context path. Si la interfaz se sirve desde la misma aplicación, los campos se rellenan con la dirección actual.

## Protocolo

- El primer mensaje WebSocket es `{"type":"hello","name":"..."}`. El servidor rechaza seudónimos vacíos o repetidos y limita las conexiones a 50 por instancia. Puede cambiarse con `-Dchat.maxClients=50`.
- `user_list` y `status_update` mantienen la presencia (`online`, `away`, `dnd`). `private_message` se enruta solamente a la sesión indicada.
- Los archivos se anuncian con `file_start`, el destinatario los acepta, y el navegador transmite tramas binarias de hasta 60 KiB. El servidor solo reenvía un fragmento a la vez; el destinatario confirma cada escritura antes de que el emisor envíe el siguiente. No se guarda el archivo en el disco ni se acumula en memoria del servidor. El máximo predeterminado es 20 GiB; se cambia con `-Dchat.maxFileBytes=...`.
- En Chrome o Edge, el destinatario elige una ruta de guardado y el navegador escribe progresivamente en disco. En navegadores sin File System Access, se acumulan fragmentos en memoria y se rechazan archivos mayores a 512 MiB.
- `signal` reenvía SDP e ICE solo al par designado. `room_create`, `room_join` y `room_leave` habilitan salas; las llamadas grupales usan conexiones WebRTC en malla. El servidor no retransmite audio o video.

El servidor WebSocket no requiere configuración de IP/puerto aparte del conector normal de Tomcat. Para producción, usa HTTPS/WSS. La app es un prototipo funcional: no incorpora cuentas ni autenticación, y las llamadas usan un servidor STUN público sin TURN. TURN mejora la conexión cuando la red del usuario bloquea conexiones P2P. Para despliegues con varias instancias, hace falta presencia compartida y enrutamiento entre instancias; el mapa en memoria actual funciona con una sola.

## Panel de usuarios conectados

Abre `/Chat-Virtual/admin.html` y escribe el mismo token secreto que configuraste en el proceso Tomcat. El panel solo recibe seudónimos, estado e ID de sesión, y se actualiza al conectarse, desconectarse o cambiar de estado un usuario. Sin token configurado, el endpoint administrativo permanece deshabilitado.

En Windows, para Tomcat iniciado con `startup.bat`, configura `CHAT_ADMIN_TOKEN` en `bin/setenv.bat` antes de iniciar o reiniciar Tomcat:

```bat
set "CHAT_ADMIN_TOKEN=REEMPLAZA_POR_UN_TOKEN_LARGO_Y_ALEATORIO"
```

Si Tomcat corre como servicio de Windows, define `CHAT_ADMIN_TOKEN` para ese servicio o añade `-Dchat.adminToken=...` en sus opciones Java y reinícialo. No publiques el token ni lo pongas en el repositorio.

## Despliegue gratuito de prueba en Render

Render acepta aplicaciones Java mediante Docker y admite conexiones WebSocket. Este repositorio incluye un `Dockerfile` que empaqueta el WAR y ejecuta Tomcat en el puerto que Render asigne:

1. Sube el proyecto a un repositorio Git.
2. En Render crea **New → Web Service**, conecta el repositorio y elige el plan **Free**.
3. Render detecta el `Dockerfile` y construye el WAR. Abre la URL HTTPS asignada; los campos del formulario mostrarán el host, puerto y context path raíz.

El plan gratuito sirve para probar, no para producción: el servicio se duerme tras 15 minutos sin tráfico, puede tardar cerca de un minuto en reactivarse, tiene un máximo mensual de 750 horas gratuitas compartidas por workspace y su sistema de archivos es efímero. El servicio no conserva archivos en el servidor, pero las llamadas y transferencias dependen de que la instancia siga activa. El consumo de datos salientes también puede quedar sujeto a los límites del plan. Render despliega el WAR dentro de Tomcat mediante Docker; no es una consola para cargar WAR manualmente.

Para un WAR que se pueda cargar directamente en una consola Tomcat gratuita, las opciones gratuitas estables son escasas. Un VPS gratuito donde instales Tomcat por tu cuenta puede servir, sujeto a disponibilidad, límites y requisitos de cuenta del proveedor. Comprueba esos términos antes de usarlo para algo más que una demostración.
