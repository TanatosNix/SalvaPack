# SalvaPack

App Android en español para gestionar entre dos personas una mochila de salvamento. Permite crear, editar y borrar fichas con nombre, categoría, foto y fecha de caducidad. Las categorías son **Comida**, **Medicamentos** y **Emergencia**. Cada móvil avisa un mes natural antes de la fecha indicada.

La versión inicial utiliza Kotlin, Jetpack Compose y Firebase Authentication/Firestore. Requiere Android 8.0 o posterior.

## Probar la app

Abre esta carpeta en Android Studio, deja que Gradle termine la sincronización y ejecuta `app` en un móvil o emulador. Instala el SDK de Android 36 si Android Studio lo solicita y utiliza el JDK incluido con Android Studio para Gradle.

También puedes compilar desde PowerShell, con el SDK y Java configurados:

```powershell
.\gradlew.bat :app:assembleDebug
```

El APK se genera en `app/build/outputs/apk/debug/app-debug.apk`. Puedes copiarlo al móvil y abrirlo, autorizando la instalación desde esa aplicación cuando Android lo solicite. Es una compilación de desarrollo para pruebas privadas.

**Sin `app/google-services.json`, la app funciona en modo local:** guarda los productos y las fotos en ese móvil y programa sus avisos. Este modo permite probarla antes de crear Firebase; no sincroniza entre teléfonos. Desinstalar o borrar los datos de la app elimina ese inventario local.

**Para compartir la mochila**, sigue [la configuración de Firebase paso a paso](docs/CONFIGURAR_FIREBASE.md), añade el archivo de configuración y vuelve a compilar. Los dos móviles deben instalar una versión conectada al mismo proyecto. Los productos de la prueba local no se importan automáticamente a la mochila compartida.

## Usarla entre los dos

1. Una persona crea la mochila en el primer móvil y comparte su código completo de 32 caracteres en privado.
2. En el segundo móvil se elige **Unirme con un código** y se introduce ese código.
3. No hay registro, correo, usuario ni contraseña. Firebase identifica cada instalación de forma anónima y automática.
4. Ambos pueden modificar el mismo inventario. Los cambios se reciben cuando hay conexión; la app conserva una copia para el uso sin conexión.
5. Concede el permiso de notificaciones en cada móvil para recibir los avisos.

La aplicación está pensada para uso privado en vuestros dos móviles. El código funciona como llave de acceso y recuperación: guardadlo en un lugar seguro y compartidlo solo entre vosotros. Si cambiáis de móvil o reinstaláis la app, podréis volver a entrar con el mismo código. No se publica ningún listado de mochilas y un dispositivo que no conozca el código no puede acceder al inventario.

## Cómo funcionan las alertas

El aviso se calcula restando **un mes natural**, no 30 días: para el 31 de marzo avisa el 28 de febrero, o el 29 si es bisiesto. La hora objetivo es las 09:00, según la zona horaria del móvil. Si guardas un producto que ya está dentro de ese mes o caducado, se avisa al revisar el inventario. Un producto sin fecha no genera aviso.

Se entrega un aviso por producto y fecha de caducidad; no hay un segundo aviso automático el día que caduca. Al cambiar la fecha se recalcula; al borrar el producto se cancela. Cada móvil gestiona sus notificaciones y revisa los cambios compartidos en segundo plano aproximadamente cada 12 horas.

Android puede retrasar las alarmas y el trabajo en segundo plano por ahorro de batería o restricciones del sistema. Forzar la detención de la app puede impedir los avisos hasta volver a abrirla. Sin conexión, solo se conocen los productos ya sincronizados; las nuevas fichas del otro móvil requieren sincronización para programar su aviso. La app no garantiza una notificación a una hora exacta. Véanse [alarmas de Android](https://developer.android.com/develop/background-work/services/alarms) y [trabajo periódico](https://developer.android.com/reference/androidx/work/PeriodicWorkRequest).

## Datos y comprobaciones

Las fotos se reducen y comprimen antes de guardarse junto a la ficha en Firestore. No se utiliza Cloud Storage ni se publican enlaces a las fotos. Las reglas exigen una identidad anónima del dispositivo vinculada mediante el código privado. Si ambos editan la misma ficha simultáneamente, prevalece la última escritura que recibe Firestore; no hay un historial de versiones.

Las reglas y sus pruebas están en `firebase/`. Para ejecutarlas con Node.js 20 o posterior y Java 21 o posterior:

```powershell
npm --prefix firebase ci
npm --prefix firebase test
```

Estas pruebas usan exclusivamente el proyecto ficticio `demo-salvapack` en el emulador local, sin acceso a una base de datos real. Comprueban la creación y unión mediante código, el aislamiento entre dispositivos, los datos inválidos y los permisos de las fichas. Las pruebas de fechas y recordatorios se ejecutan con:

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

La [lista de comprobaciones en dos móviles](docs/PRUEBA_EN_MOVILES.md) permite verificar la sincronización real y los permisos de cada dispositivo tras configurar Firebase.
