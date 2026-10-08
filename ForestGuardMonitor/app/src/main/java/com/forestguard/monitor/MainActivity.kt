package com.forestguard.monitor

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Patterns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.forestguard.monitor.model.ForestState
import com.forestguard.monitor.model.AppMode
import com.forestguard.monitor.data.SensorReading
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

const val DEFAULT_UMBRAL_HUMO = 35000

const val NOTIFICATION_CHANNEL_ID =
    "forestguard_alertas"

const val NOTIFICATION_CHANNEL_NAME =
    "Alertas ForestGuard"

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        crearCanalNotificaciones(this)

        val auth =
            FirebaseAuth.getInstance()

        val firestore =
            FirebaseFirestore.getInstance()

        setContent {

            MaterialTheme {

                ForestGuardApp(
                    auth = auth,
                    firestore = firestore
                )
            }
        }
    }
}

@Composable
fun ForestGuardApp(
    auth: FirebaseAuth,
    firestore: FirebaseFirestore
) {
    var currentUser by remember {
        mutableStateOf(auth.currentUser)
    }

    var userRole by remember {
        mutableStateOf<AppMode?>(null)
    }

    var isLoadingRole by remember {
        mutableStateOf(auth.currentUser != null)
    }

    var roleError by remember {
        mutableStateOf<String?>(null)
    }

    LaunchedEffect(currentUser?.uid) {
        val user = currentUser

        if (user == null) {
            userRole = null
            isLoadingRole = false
            roleError = null
            return@LaunchedEffect
        }

        isLoadingRole = true
        userRole = null
        roleError = null

        firestore
            .collection("usuarios")
            .document(user.uid)
            .get()
            .addOnSuccessListener { document ->
                userRole =
                    when (document.getString("rol")) {
                        "NODO" -> AppMode.NODO
                        "MONITOR" -> AppMode.MONITOR
                        else -> null
                    }

                roleError =
                    if (userRole == null) {
                        "La cuenta no tiene un rol asignado."
                    } else {
                        null
                    }

                isLoadingRole = false
            }
            .addOnFailureListener {
                userRole = null
                roleError =
                    it.localizedMessage
                        ?: "No fue posible consultar el rol."
                isLoadingRole = false
            }
    }

    if (currentUser == null) {
        AuthScreen(
            auth = auth,
            firestore = firestore,
            onAuthSuccess = {
                currentUser = auth.currentUser
            }
        )

        return
    }

    if (isLoadingRole) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }

        return
    }

    if (roleError != null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = roleError ?: "No fue posible cargar la cuenta.",
                color = MaterialTheme.colorScheme.error
            )

            Spacer(
                modifier = Modifier.height(16.dp)
            )

            Button(
                onClick = {
                    auth.signOut()
                    currentUser = null
                    userRole = null
                    roleError = null
                }
            ) {
                Text("Cerrar sesión")
            }
        }

        return
    }

    when (userRole) {
        AppMode.NODO -> {
            NodeScreen(
                firestore = firestore,
                nodeId =
                    currentUser?.uid
                        ?: "nodo_desconocido",
                onLogout = {
                    auth.signOut()
                    currentUser = null
                    userRole = null
                }
            )
        }

        AppMode.MONITOR -> {
            MonitorScreen(
                firestore = firestore,
                onLogout = {
                    auth.signOut()
                    currentUser = null
                    userRole = null
                }
            )
        }

        null -> Unit
    }
}

@Composable
fun NodeScreen(
    firestore: FirebaseFirestore,
    nodeId: String,
    onLogout: () -> Unit
) {

    var temperatura by rememberSaveable {
        mutableStateOf(25f)
    }

    var humedad by rememberSaveable {
        mutableStateOf(50f)
    }

    var humo by rememberSaveable {
        mutableStateOf(12000f)
    }

    var envioAutomatico by rememberSaveable {
        mutableStateOf(true)
    }

    var lecturasEnviadas by remember {
        mutableIntStateOf(0)
    }

    var mensajeFirebase by remember {

        mutableStateOf(
            "Preparando transmisión..."
        )
    }



    var umbralHumo by remember {

        mutableIntStateOf(
            DEFAULT_UMBRAL_HUMO
        )
    }

    var silenciarAlarma by remember {

        mutableStateOf(false)
    }

    var estadoConfiguracion by remember {

        mutableStateOf(
            "Esperando configuración remota..."
        )
    }



    DisposableEffect(firestore) {

        val registration =
            firestore
                .collection("comandos")
                .document("config")
                .addSnapshotListener { snapshot, error ->

                    if (error != null) {

                        estadoConfiguracion =
                            "Error al recibir configuración."

                        return@addSnapshotListener
                    }

                    if (
                        snapshot == null ||
                        !snapshot.exists()
                    ) {

                        estadoConfiguracion =
                            "Usando configuración predeterminada."

                        return@addSnapshotListener
                    }

                    umbralHumo =
                        snapshot
                            .getLong("umbralHumo")
                            ?.toInt()
                            ?: DEFAULT_UMBRAL_HUMO

                    silenciarAlarma =
                        snapshot
                            .getBoolean("silenciar")
                            ?: false

                    estadoConfiguracion =
                        "Configuración remota sincronizada."
                }

        onDispose {

            registration.remove()
        }
    }



    val estado =
        calcularEstadoForestGuard(

            temperatura =
                temperatura,

            humedad =
                humedad,

            humo =
                humo.roundToInt(),

            umbralHumo =
                umbralHumo
        )

    val temperaturaActual by
    rememberUpdatedState(
        temperatura
    )

    val humedadActual by
    rememberUpdatedState(
        humedad
    )

    val humoActual by
    rememberUpdatedState(
        humo
    )

    val umbralActual by
    rememberUpdatedState(
        umbralHumo
    )



    LaunchedEffect(
        envioAutomatico,
        nodeId
    ) {

        if (!envioAutomatico) {

            mensajeFirebase =
                "Transmisión automática detenida."

            return@LaunchedEffect
        }

        while (true) {

            val humoInt =
                humoActual.roundToInt()

            val estadoActual =
                calcularEstadoForestGuard(

                    temperatura =
                        temperaturaActual,

                    humedad =
                        humedadActual,

                    humo =
                        humoInt,

                    umbralHumo =
                        umbralActual
                )

            enviarLecturaFirestore(

                firestore =
                    firestore,

                nodeId =
                    nodeId,

                temperatura =
                    temperaturaActual,

                humedad =
                    humedadActual,

                humo =
                    humoInt,

                estado =
                    estadoActual,

                onSuccess = {

                    lecturasEnviadas++

                    mensajeFirebase =
                        "Lectura enviada correctamente."
                },

                onError = {

                    mensajeFirebase =
                        "Error: $it"
                }
            )

            delay(2000)
        }
    }



    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(
                rememberScrollState()
            )
            .padding(24.dp)
    ) {

        Text(
            text =
                "ForestGuard Monitor",

            style =
                MaterialTheme.typography.headlineMedium,

            fontWeight =
                FontWeight.Bold
        )

        Spacer(
            modifier =
                Modifier.height(4.dp)
        )

        Text(
            text =
                "Nodo de sensores",

            style =
                MaterialTheme.typography.titleMedium
        )

        Spacer(
            modifier =
                Modifier.height(24.dp)
        )



        Card(
            modifier =
                Modifier.fillMaxWidth()
        ) {

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {

                Text(
                    text =
                        "Estado actual",

                    fontWeight =
                        FontWeight.Bold
                )

                Spacer(
                    modifier =
                        Modifier.height(8.dp)
                )

                Text(
                    text = when (estado) {

                        ForestState.NORMAL ->
                            "NORMAL"

                        ForestState.PREALERTA ->
                            "PREALERTA"

                        ForestState.ALERTA_MAXIMA ->
                            "ALERTA MÁXIMA"
                    },

                    style =
                        MaterialTheme.typography.headlineSmall,

                    fontWeight =
                        FontWeight.Bold
                )

                Spacer(
                    modifier =
                        Modifier.height(8.dp)
                )

                Text(
                    text =
                        obtenerDescripcionEstado(
                            estado = estado,
                            umbralHumo = umbralHumo
                        )
                )
            }
        }

        Spacer(
            modifier =
                Modifier.height(20.dp)
        )



        ElevatedCard(
            modifier =
                Modifier.fillMaxWidth()
        ) {

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {

                Text(
                    text =
                        "Configuración remota",

                    style =
                        MaterialTheme.typography.titleMedium,

                    fontWeight =
                        FontWeight.Bold
                )

                Spacer(
                    modifier =
                        Modifier.height(8.dp)
                )

                Text(
                    text =
                        "Umbral de humo: $umbralHumo"
                )

                Text(
                    text =
                        if (silenciarAlarma) {

                            "Alarma remota: SILENCIADA"

                        } else {

                            "Alarma remota: ACTIVA"
                        }
                )

                Spacer(
                    modifier =
                        Modifier.height(4.dp)
                )

                Text(
                    text =
                        estadoConfiguracion,

                    style =
                        MaterialTheme.typography.bodySmall
                )
            }
        }

        Spacer(
            modifier =
                Modifier.height(28.dp)
        )



        Text(
            text =
                "Temperatura",

            style =
                MaterialTheme.typography.titleMedium,

            fontWeight =
                FontWeight.Bold
        )

        Text(
            text =
                "${formatOneDecimal(temperatura)} °C",

            style =
                MaterialTheme.typography.headlineSmall
        )

        Slider(

            value =
                temperatura,

            onValueChange = {

                temperatura = it
            },

            valueRange =
                0f..50f
        )

        Spacer(
            modifier =
                Modifier.height(24.dp)
        )



        Text(
            text =
                "Humedad",

            style =
                MaterialTheme.typography.titleMedium,

            fontWeight =
                FontWeight.Bold
        )

        Text(
            text =
                "${formatOneDecimal(humedad)} %",

            style =
                MaterialTheme.typography.headlineSmall
        )

        Slider(

            value =
                humedad,

            onValueChange = {

                humedad = it
            },

            valueRange =
                0f..100f
        )

        Spacer(
            modifier =
                Modifier.height(24.dp)
        )



        Text(
            text =
                "Humo / Gas",

            style =
                MaterialTheme.typography.titleMedium,

            fontWeight =
                FontWeight.Bold
        )

        Text(
            text =
                humo
                    .roundToInt()
                    .toString(),

            style =
                MaterialTheme.typography.headlineSmall
        )

        Slider(

            value =
                humo,

            onValueChange = {

                humo = it
            },

            valueRange =
                0f..65535f
        )

        Text(
            text =
                "Umbral remoto actual: $umbralHumo",

            style =
                MaterialTheme.typography.bodySmall
        )

        Spacer(
            modifier =
                Modifier.height(32.dp)
        )



        Row(
            modifier =
                Modifier.fillMaxWidth(),

            verticalAlignment =
                Alignment.CenterVertically,

            horizontalArrangement =
                Arrangement.SpaceBetween
        ) {

            Column(
                modifier =
                    Modifier.weight(1f)
            ) {

                Text(
                    text =
                        "Transmisión automática",

                    fontWeight =
                        FontWeight.Bold
                )

                Text(
                    text =
                        "Envía una lectura cada 2 segundos.",

                    style =
                        MaterialTheme.typography.bodySmall
                )
            }

            Switch(

                checked =
                    envioAutomatico,

                onCheckedChange = {

                    envioAutomatico = it
                }
            )
        }

        Spacer(
            modifier =
                Modifier.height(20.dp)
        )

        ElevatedCard(
            modifier =
                Modifier.fillMaxWidth()
        ) {

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {

                Text(
                    text =
                        "Cloud Firestore",

                    fontWeight =
                        FontWeight.Bold
                )

                Spacer(
                    modifier =
                        Modifier.height(8.dp)
                )

                Text(
                    text =
                        "Lecturas enviadas: $lecturasEnviadas"
                )

                Text(
                    text =
                        mensajeFirebase
                )
            }
        }

        Spacer(
            modifier =
                Modifier.height(20.dp)
        )



        Button(
            onClick = {

                val humoInt =
                    humo.roundToInt()

                val estadoActual =
                    calcularEstadoForestGuard(

                        temperatura =
                            temperatura,

                        humedad =
                            humedad,

                        humo =
                            humoInt,

                        umbralHumo =
                            umbralHumo
                    )

                enviarLecturaFirestore(

                    firestore =
                        firestore,

                    nodeId =
                        nodeId,

                    temperatura =
                        temperatura,

                    humedad =
                        humedad,

                    humo =
                        humoInt,

                    estado =
                        estadoActual,

                    onSuccess = {

                        lecturasEnviadas++

                        mensajeFirebase =
                            "Lectura manual enviada correctamente."
                    },

                    onError = {

                        mensajeFirebase =
                            "Error: $it"
                    }
                )
            },

            modifier =
                Modifier.fillMaxWidth()
        ) {

            Text(
                text =
                    "Enviar lectura ahora"
            )
        }

        Spacer(
            modifier =
                Modifier.height(24.dp)
        )
        TextButton(

            onClick =
                onLogout,

            modifier =
                Modifier.fillMaxWidth()
        ) {

            Text(
                text =
                    "Cerrar sesión"
            )
        }

        Spacer(
            modifier =
                Modifier.height(24.dp)
        )
    }
}

@Composable
fun MonitorScreen(
    firestore: FirebaseFirestore,
    onLogout: () -> Unit
) {

    val context =
        LocalContext.current

    var ultimaLectura by remember {
        mutableStateOf<SensorReading?>(null)
    }

    var historial by remember {

        mutableStateOf(
            emptyList<SensorReading>()
        )
    }

    var estadoConexion by remember {

        mutableStateOf(
            "Esperando datos..."
        )
    }

    var estadoAnterior by remember {

        mutableStateOf<String?>(null)
    }



    var umbralHumo by remember {

        mutableIntStateOf(
            DEFAULT_UMBRAL_HUMO
        )
    }

    var campoUmbral by remember {

        mutableStateOf(
            DEFAULT_UMBRAL_HUMO.toString()
        )
    }

    var silenciarAlarma by remember {

        mutableStateOf(false)
    }

    var mensajeControl by remember {

        mutableStateOf(
            "Sincronizando configuración..."
        )
    }



    var permisoNotificaciones by remember {

        mutableStateOf(

            Build.VERSION.SDK_INT < 33 ||

                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(

            contract =
                ActivityResultContracts.RequestPermission()

        ) {

            permisoNotificaciones =
                it
        }

    LaunchedEffect(Unit) {

        if (
            Build.VERSION.SDK_INT >= 33 &&
            !permisoNotificaciones
        ) {

            permissionLauncher.launch(
                Manifest.permission.POST_NOTIFICATIONS
            )
        }
    }



    DisposableEffect(firestore) {

        val configRef =
            firestore
                .collection("comandos")
                .document("config")

        val registration =
            configRef
                .addSnapshotListener { snapshot, error ->

                    if (error != null) {

                        mensajeControl =
                            "Error al recibir configuración."

                        return@addSnapshotListener
                    }



                    if (
                        snapshot == null ||
                        !snapshot.exists()
                    ) {

                        val configInicial =
                            hashMapOf<String, Any>(

                                "umbralHumo" to
                                        DEFAULT_UMBRAL_HUMO,

                                "silenciar" to
                                        false
                            )

                        configRef.set(
                            configInicial,
                            SetOptions.merge()
                        )

                        return@addSnapshotListener
                    }

                    val nuevoUmbral =
                        snapshot
                            .getLong("umbralHumo")
                            ?.toInt()
                            ?: DEFAULT_UMBRAL_HUMO

                    umbralHumo =
                        nuevoUmbral

                    campoUmbral =
                        nuevoUmbral.toString()

                    silenciarAlarma =
                        snapshot
                            .getBoolean("silenciar")
                            ?: false

                    mensajeControl =
                        "Configuración sincronizada."
                }

        onDispose {

            registration.remove()
        }
    }



    DisposableEffect(firestore) {

        estadoConexion =
            "Conectando con Firestore..."

        val registration =
            firestore
                .collection("lecturas")
                .orderBy(
                    "timestamp",
                    Query.Direction.DESCENDING
                )
                .limit(10)
                .addSnapshotListener { snapshot, error ->

                    if (error != null) {

                        estadoConexion =
                            "Error: ${
                                error.localizedMessage
                            }"

                        return@addSnapshotListener
                    }

                    if (
                        snapshot == null ||
                        snapshot.isEmpty
                    ) {

                        ultimaLectura =
                            null

                        historial =
                            emptyList()

                        estadoConexion =
                            "No existen lecturas."

                        return@addSnapshotListener
                    }

                    val lecturas =
                        snapshot.documents.map {

                            SensorReading(

                                id =
                                    it.id,

                                temperatura =
                                    it.getDouble(
                                        "temperatura"
                                    ) ?: 0.0,

                                humedad =
                                    it.getDouble(
                                        "humedad"
                                    ) ?: 0.0,

                                humo =
                                    it.getLong(
                                        "humo"
                                    ) ?: 0L,

                                estado =
                                    it.getString(
                                        "estado"
                                    ) ?: "DESCONOCIDO",

                                timestamp =
                                    it.getTimestamp(
                                        "timestamp"
                                    ),

                                nodo =
                                    it.getString(
                                        "nodo"
                                    ) ?: "desconocido"
                            )
                        }

                    historial =
                        lecturas

                    ultimaLectura =
                        lecturas.firstOrNull()

                    estadoConexion =
                        "Conectado en tiempo real"
                }

        onDispose {

            registration.remove()
        }
    }



    LaunchedEffect(
        ultimaLectura?.id,
        silenciarAlarma
    ) {

        val lectura =
            ultimaLectura
                ?: return@LaunchedEffect



        if (
            lectura.estado ==
            ForestState.ALERTA_MAXIMA.firestoreValue
            &&
            estadoAnterior !=
            ForestState.ALERTA_MAXIMA.firestoreValue
            &&
            !silenciarAlarma
        ) {

            emitirAlertaLocal(

                context =
                    context,

                temperatura =
                    lectura.temperatura,

                humedad =
                    lectura.humedad,

                humo =
                    lectura.humo
            )
        }

        estadoAnterior =
            lectura.estado
    }



    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(
                rememberScrollState()
            )
            .padding(24.dp)
    ) {

        Text(
            text =
                "ForestGuard Monitor",

            style =
                MaterialTheme.typography.headlineMedium,

            fontWeight =
                FontWeight.Bold
        )

        Text(
            text =
                "Centro de monitoreo",

            style =
                MaterialTheme.typography.titleMedium
        )

        Spacer(
            modifier =
                Modifier.height(20.dp)
        )



        ElevatedCard(
            modifier =
                Modifier.fillMaxWidth()
        ) {

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {

                Text(
                    text =
                        "Conexión",

                    fontWeight =
                        FontWeight.Bold
                )

                Text(
                    text =
                        estadoConexion
                )
            }
        }

        Spacer(
            modifier =
                Modifier.height(20.dp)
        )



        ElevatedCard(
            modifier =
                Modifier.fillMaxWidth()
        ) {

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {

                Text(
                    text =
                        "Control remoto",

                    style =
                        MaterialTheme.typography.titleLarge,

                    fontWeight =
                        FontWeight.Bold
                )

                Spacer(
                    modifier =
                        Modifier.height(8.dp)
                )

                Text(
                    text =
                        "Umbral actual de humo: $umbralHumo"
                )

                Spacer(
                    modifier =
                        Modifier.height(12.dp)
                )

                OutlinedTextField(

                    value =
                        campoUmbral,

                    onValueChange = {

                        campoUmbral =
                            it.filter { caracter ->

                                caracter.isDigit()
                            }
                    },

                    modifier =
                        Modifier.fillMaxWidth(),

                    label = {

                        Text(
                            "Nuevo umbral de humo"
                        )
                    },

                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType =
                                KeyboardType.Number
                        ),

                    singleLine =
                        true
                )

                Spacer(
                    modifier =
                        Modifier.height(12.dp)
                )

                Button(
                    onClick = {

                        val nuevoUmbral =
                            campoUmbral.toIntOrNull()

                        if (
                            nuevoUmbral == null
                        ) {

                            mensajeControl =
                                "Ingresa un número válido."

                            return@Button
                        }

                        if (
                            nuevoUmbral < 1000 ||
                            nuevoUmbral > 65535
                        ) {

                            mensajeControl =
                                "El umbral debe estar entre 1000 y 65535."

                            return@Button
                        }

                        firestore
                            .collection("comandos")
                            .document("config")
                            .set(

                                mapOf(
                                    "umbralHumo" to
                                            nuevoUmbral
                                ),

                                SetOptions.merge()
                            )
                            .addOnSuccessListener {

                                mensajeControl =
                                    "Umbral enviado al Nodo."
                            }
                            .addOnFailureListener {

                                mensajeControl =
                                    "No fue posible cambiar el umbral."
                            }
                    },

                    modifier =
                        Modifier.fillMaxWidth()
                ) {

                    Text(
                        text =
                            "Guardar nuevo umbral"
                    )
                }

                Spacer(
                    modifier =
                        Modifier.height(16.dp)
                )



                OutlinedButton(
                    onClick = {

                        val nuevoEstado =
                            !silenciarAlarma

                        firestore
                            .collection("comandos")
                            .document("config")
                            .set(

                                mapOf(
                                    "silenciar" to
                                            nuevoEstado
                                ),

                                SetOptions.merge()
                            )
                            .addOnSuccessListener {

                                mensajeControl =
                                    if (nuevoEstado) {

                                        "Comando de silencio enviado."

                                    } else {

                                        "Alarma reactivada."
                                    }
                            }
                            .addOnFailureListener {

                                mensajeControl =
                                    "No fue posible enviar el comando."
                            }
                    },

                    modifier =
                        Modifier.fillMaxWidth()
                ) {

                    Text(
                        text =
                            if (silenciarAlarma) {

                                "Reactivar alarma"

                            } else {

                                "Silenciar alarma"
                            }
                    )
                }

                Spacer(
                    modifier =
                        Modifier.height(8.dp)
                )

                Text(
                    text =
                        if (silenciarAlarma) {

                            "🔇 Alarma silenciada"

                        } else {

                            "🔊 Alarma activa"
                        },

                    fontWeight =
                        FontWeight.Bold
                )

                Spacer(
                    modifier =
                        Modifier.height(4.dp)
                )

                Text(
                    text =
                        mensajeControl,

                    style =
                        MaterialTheme.typography.bodySmall
                )
            }
        }

        Spacer(
            modifier =
                Modifier.height(24.dp)
        )

        if (
            ultimaLectura == null
        ) {

            Card(
                modifier =
                    Modifier.fillMaxWidth()
            ) {

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),

                    horizontalAlignment =
                        Alignment.CenterHorizontally
                ) {

                    CircularProgressIndicator()

                    Spacer(
                        modifier =
                            Modifier.height(16.dp)
                    )

                    Text(
                        text =
                            "Esperando lecturas del Nodo..."
                    )
                }
            }

        } else {

            val lectura =
                ultimaLectura!!

            EstadoSemaforoCard(
                estado =
                    lectura.estado
            )

            Spacer(
                modifier =
                    Modifier.height(24.dp)
            )

            SensorMonitorCard(

                titulo =
                    "Temperatura",

                valor =
                    "${
                        formatOneDecimal(
                            lectura
                                .temperatura
                                .toFloat()
                        )
                    } °C",

                descripcion =
                    "Sensor simulado DS18B20"
            )

            Spacer(
                modifier =
                    Modifier.height(12.dp)
            )

            SensorMonitorCard(

                titulo =
                    "Humedad",

                valor =
                    "${
                        formatOneDecimal(
                            lectura
                                .humedad
                                .toFloat()
                        )
                    } %",

                descripcion =
                    "Sensor simulado DHT22"
            )

            Spacer(
                modifier =
                    Modifier.height(12.dp)
            )

            SensorMonitorCard(

                titulo =
                    "Humo / Gas",

                valor =
                    lectura.humo.toString(),

                descripcion =
                    "Sensor simulado MQ-2"
            )

            Spacer(
                modifier =
                    Modifier.height(20.dp)
            )

            Text(
                text =
                    "Última actualización: ${
                        formatearTimestamp(
                            lectura.timestamp
                        )
                    }"
            )

            Spacer(
                modifier =
                    Modifier.height(32.dp)
            )

            Text(
                text =
                    "Historial de últimas lecturas",

                style =
                    MaterialTheme.typography.titleLarge,

                fontWeight =
                    FontWeight.Bold
            )

            Spacer(
                modifier =
                    Modifier.height(12.dp)
            )

            historial.forEach {

                HistorialCard(
                    lectura = it
                )

                Spacer(
                    modifier =
                        Modifier.height(8.dp)
                )
            }
        }

        Spacer(
            modifier =
                Modifier.height(24.dp)
        )
        TextButton(
            onClick =
                onLogout,

            modifier =
                Modifier.fillMaxWidth()
        ) {

            Text(
                text =
                    "Cerrar sesión"
            )
        }

        Spacer(
            modifier =
                Modifier.height(24.dp)
        )
    }
}

fun calcularEstadoForestGuard(
    temperatura: Float,
    humedad: Float,
    humo: Int,
    umbralHumo: Int
): ForestState {



    if (
        humo > umbralHumo
    ) {

        return ForestState.ALERTA_MAXIMA
    }



    if (
        temperatura >= 30f &&
        humedad <= 30f
    ) {

        return ForestState.PREALERTA
    }

    return ForestState.NORMAL
}

fun obtenerDescripcionEstado(
    estado: ForestState,
    umbralHumo: Int
): String {

    return when (estado) {

        ForestState.NORMAL -> {

            "Las variables se encuentran dentro " +
                    "de condiciones normales."
        }

        ForestState.PREALERTA -> {

            "Temperatura igual o superior a 30 °C " +
                    "y humedad igual o inferior al 30 %."
        }

        ForestState.ALERTA_MAXIMA -> {

            "El humo superó el umbral remoto " +
                    "de $umbralHumo."
        }
    }
}

fun enviarLecturaFirestore(
    firestore: FirebaseFirestore,
    nodeId: String,
    temperatura: Float,
    humedad: Float,
    humo: Int,
    estado: ForestState,
    onSuccess: () -> Unit,
    onError: (String) -> Unit
) {

    val lectura =
        hashMapOf<String, Any>(

            "temperatura" to
                    temperatura.toDouble(),

            "humedad" to
                    humedad.toDouble(),

            "humo" to
                    humo,

            "estado" to
                    estado.firestoreValue,

            "timestamp" to
                    FieldValue.serverTimestamp(),

            "nodo" to
                    nodeId
        )

    firestore
        .collection("lecturas")
        .add(lectura)
        .addOnSuccessListener {

            onSuccess()
        }
        .addOnFailureListener {

            onError(
                it.localizedMessage
                    ?: "Error desconocido"
            )
        }
}

@Composable
fun EstadoSemaforoCard(
    estado: String
) {

    val color =
        obtenerColorEstado(
            estado
        )

    val texto =
        obtenerNombreEstado(
            estado
        )

    val textoColor =
        if (
            estado ==
            ForestState.PREALERTA.firestoreValue
        ) {

            Color.Black

        } else {

            Color.White
        }

    Card(
        modifier =
            Modifier.fillMaxWidth()
    ) {

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),

            horizontalAlignment =
                Alignment.CenterHorizontally
        ) {

            Text(
                text =
                    "Estado ForestGuard",

                style =
                    MaterialTheme.typography.titleMedium,

                fontWeight =
                    FontWeight.Bold
            )

            Spacer(
                modifier =
                    Modifier.height(16.dp)
            )

            Box(
                modifier = Modifier
                    .size(90.dp)
                    .background(
                        color,
                        CircleShape
                    ),

                contentAlignment =
                    Alignment.Center
            ) {

                Text(
                    text = when (estado) {

                        "NORMAL" ->
                            "OK"

                        "PREALERTA" ->
                            "!"

                        "ALERTA_MAXIMA" ->
                            "!!"

                        else ->
                            "?"
                    },

                    color =
                        textoColor,

                    fontWeight =
                        FontWeight.Bold
                )
            }

            Spacer(
                modifier =
                    Modifier.height(16.dp)
            )

            Text(
                text =
                    texto,

                style =
                    MaterialTheme.typography.headlineSmall,

                fontWeight =
                    FontWeight.Bold
            )
        }
    }
}

@Composable
fun SensorMonitorCard(
    titulo: String,
    valor: String,
    descripcion: String
) {

    ElevatedCard(
        modifier =
            Modifier.fillMaxWidth()
    ) {

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {

            Text(
                text =
                    titulo,

                fontWeight =
                    FontWeight.Bold
            )

            Spacer(
                modifier =
                    Modifier.height(6.dp)
            )

            Text(
                text =
                    valor,

                style =
                    MaterialTheme.typography.headlineMedium,

                fontWeight =
                    FontWeight.Bold
            )

            Text(
                text =
                    descripcion,

                style =
                    MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun HistorialCard(
    lectura: SensorReading
) {

    ElevatedCard(
        modifier =
            Modifier.fillMaxWidth()
    ) {

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {

            Row(
                modifier =
                    Modifier.fillMaxWidth(),

                horizontalArrangement =
                    Arrangement.SpaceBetween
            ) {

                Text(
                    text =
                        obtenerNombreEstado(
                            lectura.estado
                        ),

                    fontWeight =
                        FontWeight.Bold
                )

                Text(
                    text =
                        formatearTimestamp(
                            lectura.timestamp
                        )
                )
            }

            Spacer(
                modifier =
                    Modifier.height(8.dp)
            )

            Text(
                text =
                    "Temperatura: ${
                        formatOneDecimal(
                            lectura
                                .temperatura
                                .toFloat()
                        )
                    } °C"
            )

            Text(
                text =
                    "Humedad: ${
                        formatOneDecimal(
                            lectura
                                .humedad
                                .toFloat()
                        )
                    } %"
            )

            Text(
                text =
                    "Humo: ${lectura.humo}"
            )
        }
    }
}

fun obtenerColorEstado(
    estado: String
): Color {

    return when (estado) {

        "NORMAL" ->
            Color(0xFF2E7D32)

        "PREALERTA" ->
            Color(0xFFF9A825)

        "ALERTA_MAXIMA" ->
            Color(0xFFC62828)

        else ->
            Color.Gray
    }
}

fun obtenerNombreEstado(
    estado: String
): String {

    return when (estado) {

        "NORMAL" ->
            "🟢 NORMAL"

        "PREALERTA" ->
            "🟡 PREALERTA"

        "ALERTA_MAXIMA" ->
            "🔴 ALERTA MÁXIMA"

        else ->
            "DESCONOCIDO"
    }
}

fun formatearTimestamp(
    timestamp: Timestamp?
): String {

    if (
        timestamp == null
    ) {

        return "Sincronizando..."
    }

    val formatter =
        SimpleDateFormat(
            "HH:mm:ss",
            Locale.getDefault()
        )

    return formatter.format(
        timestamp.toDate()
    )
}

fun crearCanalNotificaciones(
    context: Context
) {

    val manager =
        context.getSystemService(
            Context.NOTIFICATION_SERVICE
        ) as NotificationManager

    val channel =
        NotificationChannel(

            NOTIFICATION_CHANNEL_ID,

            NOTIFICATION_CHANNEL_NAME,

            NotificationManager.IMPORTANCE_HIGH
        )

    channel.description =
        "Alertas críticas de ForestGuard"

    channel.enableVibration(true)

    manager.createNotificationChannel(
        channel
    )
}

fun emitirAlertaLocal(
    context: Context,
    temperatura: Double,
    humedad: Double,
    humo: Long
) {



    try {

        val uri =
            RingtoneManager.getDefaultUri(
                RingtoneManager.TYPE_NOTIFICATION
            )

        RingtoneManager
            .getRingtone(
                context,
                uri
            )
            ?.play()

    } catch (
        ignored: Exception
    ) {
    }



    try {

        val vibrator =

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.S
            ) {

                val manager =
                    context.getSystemService(
                        Context.VIBRATOR_MANAGER_SERVICE
                    ) as VibratorManager

                manager.defaultVibrator

            } else {

                @Suppress("DEPRECATION")

                context.getSystemService(
                    Context.VIBRATOR_SERVICE
                ) as Vibrator
            }

        vibrator.vibrate(

            VibrationEffect.createWaveform(

                longArrayOf(
                    0,
                    500,
                    200,
                    500
                ),

                -1
            )
        )

    } catch (
        ignored: Exception
    ) {
    }



    if (
        Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) !=
        PackageManager.PERMISSION_GRANTED
    ) {

        return
    }

    val intent =
        Intent(
            context,
            MainActivity::class.java
        )

    val pendingIntent =
        PendingIntent.getActivity(

            context,

            100,

            intent,

            PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
        )

    val notification =
        NotificationCompat.Builder(
            context,
            NOTIFICATION_CHANNEL_ID
        )
            .setSmallIcon(
                android.R.drawable.ic_dialog_alert
            )
            .setContentTitle(
                "ForestGuard: ALERTA MÁXIMA"
            )
            .setContentText(
                "Humo detectado: $humo"
            )
            .setStyle(

                NotificationCompat.BigTextStyle()
                    .bigText(

                        "Condición crítica detectada. " +

                                "Temperatura: ${
                                    formatOneDecimal(
                                        temperatura.toFloat()
                                    )
                                } °C, " +

                                "Humedad: ${
                                    formatOneDecimal(
                                        humedad.toFloat()
                                    )
                                } %, " +

                                "Humo/Gas: $humo."
                    )
            )
            .setPriority(
                NotificationCompat.PRIORITY_HIGH
            )
            .setAutoCancel(true)
            .setContentIntent(
                pendingIntent
            )
            .build()

    val manager =
        context.getSystemService(
            Context.NOTIFICATION_SERVICE
        ) as NotificationManager

    manager.notify(
        2001,
        notification
    )
}

@Composable
fun AuthScreen(
    auth: FirebaseAuth,
    firestore: FirebaseFirestore,
    onAuthSuccess: () -> Unit
) {
    var isLogin by rememberSaveable {
        mutableStateOf(true)
    }

    var email by rememberSaveable {
        mutableStateOf("")
    }

    var password by rememberSaveable {
        mutableStateOf("")
    }

    var confirmPassword by rememberSaveable {
        mutableStateOf("")
    }

    var selectedRole by rememberSaveable {
        mutableStateOf(AppMode.NODO)
    }

    var errorMessage by remember {
        mutableStateOf<String?>(null)
    }

    var isLoading by remember {
        mutableStateOf(false)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(
                rememberScrollState()
            )
            .imePadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "ForestGuard Monitor",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(
            modifier = Modifier.height(24.dp)
        )

        OutlinedTextField(
            value = email,
            onValueChange = {
                email = it
                errorMessage = null
            },
            modifier = Modifier.fillMaxWidth(),
            label = {
                Text("Correo electrónico")
            },
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = KeyboardType.Email
                ),
            singleLine = true
        )

        Spacer(
            modifier = Modifier.height(16.dp)
        )

        OutlinedTextField(
            value = password,
            onValueChange = {
                password = it
                errorMessage = null
            },
            modifier = Modifier.fillMaxWidth(),
            label = {
                Text("Contraseña")
            },
            visualTransformation =
                PasswordVisualTransformation(),
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = KeyboardType.Password
                ),
            singleLine = true
        )

        if (!isLogin) {
            Spacer(
                modifier = Modifier.height(16.dp)
            )

            OutlinedTextField(
                value = confirmPassword,
                onValueChange = {
                    confirmPassword = it
                    errorMessage = null
                },
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Text("Confirmar contraseña")
                },
                visualTransformation =
                    PasswordVisualTransformation(),
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Password
                    ),
                singleLine = true
            )

            Spacer(
                modifier = Modifier.height(24.dp)
            )

            Text(
                text = "Tipo de usuario",
                modifier = Modifier.fillMaxWidth(),
                fontWeight = FontWeight.Bold
            )

            Spacer(
                modifier = Modifier.height(8.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = selectedRole == AppMode.NODO,
                    onClick = {
                        selectedRole = AppMode.NODO
                    }
                )

                Text("Nodo")
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = selectedRole == AppMode.MONITOR,
                    onClick = {
                        selectedRole = AppMode.MONITOR
                    }
                )

                Text("Monitor")
            }
        }

        errorMessage?.let {
            Spacer(
                modifier = Modifier.height(16.dp)
            )

            Text(
                text = it,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(
            modifier = Modifier.height(24.dp)
        )

        Button(
            onClick = {
                errorMessage = null

                if (email.isBlank()) {
                    errorMessage = "Ingresa tu correo."
                    return@Button
                }

                if (
                    !Patterns.EMAIL_ADDRESS
                        .matcher(email)
                        .matches()
                ) {
                    errorMessage = "Correo inválido."
                    return@Button
                }

                if (password.isBlank()) {
                    errorMessage = "Ingresa tu contraseña."
                    return@Button
                }

                if (!isLogin) {
                    if (password.length < 6) {
                        errorMessage = "Mínimo 6 caracteres."
                        return@Button
                    }

                    if (password != confirmPassword) {
                        errorMessage = "Las contraseñas no coinciden."
                        return@Button
                    }
                }

                isLoading = true

                if (isLogin) {
                    auth
                        .signInWithEmailAndPassword(
                            email.trim(),
                            password
                        )
                        .addOnCompleteListener {
                            isLoading = false

                            if (it.isSuccessful) {
                                onAuthSuccess()
                            } else {
                                errorMessage =
                                    getFirebaseErrorMessage(
                                        it.exception
                                    )
                            }
                        }
                } else {
                    auth
                        .createUserWithEmailAndPassword(
                            email.trim(),
                            password
                        )
                        .addOnCompleteListener { task ->
                            if (!task.isSuccessful) {
                                isLoading = false
                                errorMessage =
                                    getFirebaseErrorMessage(
                                        task.exception
                                    )
                                return@addOnCompleteListener
                            }

                            val user = auth.currentUser

                            if (user == null) {
                                isLoading = false
                                errorMessage =
                                    "No fue posible crear la cuenta."
                                return@addOnCompleteListener
                            }

                            val userData =
                                hashMapOf<String, Any>(
                                    "email" to email.trim(),
                                    "rol" to selectedRole.name,
                                    "creadoEn" to
                                            FieldValue.serverTimestamp()
                                )

                            firestore
                                .collection("usuarios")
                                .document(user.uid)
                                .set(userData)
                                .addOnSuccessListener {
                                    isLoading = false
                                    onAuthSuccess()
                                }
                                .addOnFailureListener {
                                    isLoading = false
                                    errorMessage =
                                        it.localizedMessage
                                            ?: "No fue posible guardar el usuario."

                                    user.delete()
                                    auth.signOut()
                                }
                        }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading
        ) {
            if (isLoading) {
                CircularProgressIndicator()
            } else {
                Text(
                    if (isLogin) {
                        "Iniciar sesión"
                    } else {
                        "Crear cuenta"
                    }
                )
            }
        }

        TextButton(
            onClick = {
                isLogin = !isLogin
                password = ""
                confirmPassword = ""
                selectedRole = AppMode.NODO
                errorMessage = null
            },
            enabled = !isLoading
        ) {
            Text(
                if (isLogin) {
                    "¿No tienes cuenta? Crear una"
                } else {
                    "¿Ya tienes cuenta? Iniciar sesión"
                }
            )
        }
    }
}

fun getFirebaseErrorMessage(
    exception: Exception?
): String {

    return when (exception) {

        is FirebaseAuthInvalidCredentialsException ->
            "Correo o contraseña incorrectos."

        is FirebaseAuthUserCollisionException ->
            "Ese correo ya está registrado."

        is FirebaseAuthWeakPasswordException ->
            "Contraseña demasiado débil."

        else ->
            exception?.localizedMessage
                ?: "Error inesperado."
    }
}

fun formatOneDecimal(
    value: Float
): String {

    return String.format(
        Locale.US,
        "%.1f",
        value
    )
}
