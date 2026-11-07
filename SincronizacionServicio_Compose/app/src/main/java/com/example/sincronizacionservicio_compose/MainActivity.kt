package com.example.sincronizacionservicio_compose

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.random.Random

// ACCIONES DE BROADCAST
private const val ACTION_SYNC = "com.example.sincronizacionservicio_compose.ACCION_SYNC_LISTA"
private const val ACTION_SYNC_ERROR = "com.example.sincronizacionservicio_compose.ACCION_SYNC_ERROR"

private const val EXTRA_EDIFICIOS_ACTUALIZADOS = "edificios_actualizados"
private const val EXTRA_ERROR_MENSAJE = "error_mensaje"

// COLORES
private val RojoPrimario = Color(0xFFD32F2F)
private val Naranja = Color(0xFFFF8C00)
private val Amarillo = Color(0xFFFFC107)
private val Fondo = Color(0xFFFFF8EF)
private val TextoOscuro = Color(0xFF4E342E)
private val Verde = Color(0xFF388E3C)

// MAIN ACTIVITY
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val database = AppDatabase.getInstance(applicationContext)
        val dao = database.edificioDao()
        val factory = EdificiosViewModelFactory(dao)

        setContent {
            MaterialTheme {
                val viewModel: EdificiosViewModel = viewModel(factory = factory)
                PantallaSincronizacion(viewModel)
            }
        }
    }
}

// ENTIDADES
@Entity
data class EdificioEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val nombre: String,
    val categoria: String,
    @ColumnInfo(defaultValue = "0")
    val actualizado: Boolean = false
)

@Entity(
    tableName = "AmbienteEntity",
    foreignKeys = [
        ForeignKey(
            entity = EdificioEntity::class,
            parentColumns = ["id"],
            childColumns = ["edificioId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["edificioId"])]
)
data class AmbienteEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val edificioId: Int,
    val nombre: String
)

@Entity(
    tableName = "NotaEntity",
    foreignKeys = [
        ForeignKey(
            entity = AmbienteEntity::class,
            parentColumns = ["id"],
            childColumns = ["ambienteId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["ambienteId"])]
)
data class NotaEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val ambienteId: Int,
    val texto: String
)

// RELACIONES
data class AmbienteConNotas(
    @Embedded val ambiente: AmbienteEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "ambienteId"
    )
    val notas: List<NotaEntity>
)

data class EdificioConAmbientesYNotas(
    @Embedded val edificio: EdificioEntity,
    @Relation(
        entity = AmbienteEntity::class,
        parentColumn = "id",
        entityColumn = "edificioId"
    )
    val ambientes: List<AmbienteConNotas>
)

// DAO
@Dao
interface EdificioDao {
    @Insert
    suspend fun insertarEdificio(edificio: EdificioEntity): Long

    @Insert
    suspend fun insertarAmbiente(ambiente: AmbienteEntity): Long

    @Insert
    suspend fun insertarNota(nota: NotaEntity)

    @Update
    suspend fun actualizarEdificio(edificio: EdificioEntity)

    @Update
    suspend fun actualizarAmbiente(ambiente: AmbienteEntity)

    @Update
    suspend fun actualizarNota(nota: NotaEntity)

    @Delete
    suspend fun eliminarEdificio(edificio: EdificioEntity)

    @Delete
    suspend fun eliminarAmbiente(ambiente: AmbienteEntity)

    @Delete
    suspend fun eliminarNota(nota: NotaEntity)

    @Query("SELECT * FROM EdificioEntity")
    suspend fun obtenerTodos(): List<EdificioEntity>

    @Transaction
    @Query("SELECT * FROM EdificioEntity")
    suspend fun obtenerConAmbientesYNotas(): List<EdificioConAmbientesYNotas>
}

// MIGRACION ROOM
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE EdificioEntity ADD COLUMN actualizado INTEGER NOT NULL DEFAULT 0")
    }
}

// BASE DE DATOS SINGLETON
@Database(
    entities = [EdificioEntity::class, AmbienteEntity::class, NotaEntity::class],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun edificioDao(): EdificioDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "app.db"
                )
                    .addMigrations(MIGRATION_3_4)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

// VIEWMODEL
class EdificiosViewModel(
    private val dao: EdificioDao
) : ViewModel() {

    private val _edificios = MutableStateFlow<List<EdificioConAmbientesYNotas>>(emptyList())
    val edificios: StateFlow<List<EdificioConAmbientesYNotas>> = _edificios

    init {
        cargarDatos()
    }

    private fun cargarDatos() {
        viewModelScope.launch(Dispatchers.IO) {
            if (dao.obtenerTodos().isEmpty()) {
                val ed1Id = dao.insertarEdificio(
                    EdificioEntity(nombre = "Biblioteca Central", categoria = "Servicios")
                )
                val amb1Id = dao.insertarAmbiente(
                    AmbienteEntity(edificioId = ed1Id.toInt(), nombre = "Sala de lectura")
                )
                dao.insertarNota(NotaEntity(ambienteId = amb1Id.toInt(), texto = "Silencio obligatorio"))
                dao.insertarNota(NotaEntity(ambienteId = amb1Id.toInt(), texto = "Capacidad max: 50 personas"))

                dao.insertarAmbiente(
                    AmbienteEntity(edificioId = ed1Id.toInt(), nombre = "Hemeroteca")
                )

                val ed2Id = dao.insertarEdificio(
                    EdificioEntity(nombre = "Facultad de Ingeniería", categoria = "Académico")
                )
                val amb2Id = dao.insertarAmbiente(
                    AmbienteEntity(edificioId = ed2Id.toInt(), nombre = "Laboratorio de cómputo")
                )
                dao.insertarNota(NotaEntity(ambienteId = amb2Id.toInt(), texto = "Prohibido ingresar alimentos"))
            }
            recargarDatosInterno()
        }
    }

    private suspend fun recargarDatosInterno() {
        _edificios.value = dao.obtenerConAmbientesYNotas()
    }

    fun recargarDatos() {
        viewModelScope.launch(Dispatchers.IO) {
            recargarDatosInterno()
        }
    }

    fun actualizarEdificio(edificio: EdificioEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.actualizarEdificio(edificio)
            recargarDatosInterno()
        }
    }

    fun eliminarEdificio(edificio: EdificioEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.eliminarEdificio(edificio)
            recargarDatosInterno()
        }
    }

    fun actualizarAmbiente(ambiente: AmbienteEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.actualizarAmbiente(ambiente)
            recargarDatosInterno()
        }
    }

    fun eliminarAmbiente(ambiente: AmbienteEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.eliminarAmbiente(ambiente)
            recargarDatosInterno()
        }
    }
}

// FACTORY
class EdificiosViewModelFactory(
    private val dao: EdificioDao
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return EdificiosViewModel(dao) as T
    }
}

// SERVICIO DE SINCRONIZACION CON SIMULACION DE ERROR
class SincronizacionService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("SYNC", "Servicio iniciado")

        serviceScope.launch {
            try {
                delay(2500)

                // SIMULACIÓN DE ERROR (50% de probabilidad de fallar)
                val hayErrorSintetico = Random.nextBoolean()
                if (hayErrorSintetico) {
                    throw Exception("Fallo de conexión en el servidor remoto (500).")
                }

                val database = AppDatabase.getInstance(applicationContext)
                val dao = database.edificioDao()
                val listaEdificios = dao.obtenerTodos()
                var cantidadActualizados = 0

                for (edificio in listaEdificios) {
                    if (edificio.categoria.equals("Académico", ignoreCase = true) && !edificio.actualizado) {
                        dao.actualizarEdificio(edificio.copy(actualizado = true))
                        cantidadActualizados++
                    }
                }

                // BROADCAST DE ÉXITO
                val intentExito = Intent(ACTION_SYNC).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_EDIFICIOS_ACTUALIZADOS, cantidadActualizados)
                }
                sendBroadcast(intentExito)
                Log.d("SYNC", "Broadcast de ÉXITO enviado")

            } catch (e: CancellationException) {
                Log.d("SYNC", "Sincronización cancelada")
                throw e
            } catch (e: Exception) {
                Log.e("SYNC", "Error en sincronización", e)

                // BROADCAST DE ERROR DEDICADO
                val intentError = Intent(ACTION_SYNC_ERROR).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_ERROR_MENSAJE, e.message ?: "Error desconocido durante la sincronización")
                }
                sendBroadcast(intentError)
                Log.d("SYNC", "Broadcast de ERROR enviado")
            } finally {
                stopSelf(startId)
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceJob.cancel()
        super.onDestroy()
    }
}

// PANTALLA PRINCIPAL CON 2 BROADCAST RECEIVERS
@Composable
fun PantallaSincronizacion(viewModel: EdificiosViewModel) {
    val context = LocalContext.current
    val edificios by viewModel.edificios.collectAsState()
    var resultado by remember { mutableStateOf<String?>(null) }
    var esError by remember { mutableStateOf(false) }
    var sincronizando by remember { mutableStateOf(false) }

    DisposableEffect(context, viewModel) {
        val receiverExito = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val cantidad = intent?.getIntExtra(EXTRA_EDIFICIOS_ACTUALIZADOS, 0) ?: 0
                esError = false
                resultado = if (cantidad == 0) {
                    "Sincronización completada sin cambios.\nNo había edificios pendientes."
                } else {
                    "¡Éxito! Se actualizaron $cantidad edificios académicos."
                }
                sincronizando = false
                viewModel.recargarDatos()
            }
        }

        val receiverError = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val mensajeError = intent?.getStringExtra(EXTRA_ERROR_MENSAJE) ?: "Error al conectar."
                esError = true
                resultado = "⚠️ FALLO EN EL SERVICIO:\n$mensajeError"
                sincronizando = false
            }
        }

        ContextCompat.registerReceiver(
            context,
            receiverExito,
            IntentFilter(ACTION_SYNC),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        ContextCompat.registerReceiver(
            context,
            receiverError,
            IntentFilter(ACTION_SYNC_ERROR),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        onDispose {
            context.unregisterReceiver(receiverExito)
            context.unregisterReceiver(receiverError)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(colors = listOf(Fondo, Color(0xFFFFE0B2), Color(0xFFFFCCBC))))
            .statusBarsPadding()
            .padding(16.dp)
    ) {
        Text(text = "SYNC CENTER", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = RojoPrimario)
        Spacer(modifier = Modifier.height(5.dp))
        Text(text = "Sistema de sincronización de edificios", fontSize = 14.sp, color = TextoOscuro)
        Spacer(modifier = Modifier.height(15.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = "Panel de control", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = TextoOscuro)
                Spacer(modifier = Modifier.height(15.dp))

                if (sincronizando) {
                    CircularProgressIndicator(color = Naranja, strokeWidth = 5.dp)
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(text = "Sincronizando...", color = Naranja, fontWeight = FontWeight.Bold)
                } else {
                    Text(text = "Sistema listo para sincronizar", color = TextoOscuro, fontSize = 14.sp)
                }

                Spacer(modifier = Modifier.height(15.dp))

                Button(
                    onClick = {
                        resultado = null
                        esError = false
                        sincronizando = true
                        context.startService(Intent(context, SincronizacionService::class.java))
                    },
                    enabled = !sincronizando,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Naranja, disabledContainerColor = Color.LightGray)
                ) {
                    Text(text = "Sincronizar (50% Prob. Error)", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(10.dp))

                Button(
                    onClick = {
                        context.stopService(Intent(context, SincronizacionService::class.java))
                        sincronizando = false
                        esError = true
                        resultado = "Sincronización cancelada por el usuario."
                    },
                    enabled = sincronizando,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = RojoPrimario, disabledContainerColor = Color.LightGray)
                ) {
                    Text(text = "Detener sincronización", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(15.dp))
                HorizontalDivider(color = Amarillo, thickness = 2.dp)
                Spacer(modifier = Modifier.height(12.dp))

                resultado?.let {
                    Text(
                        text = it,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (esError) RojoPrimario else Verde
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))
        Text(text = "Edificios registrados en Room", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = RojoPrimario)
        Spacer(modifier = Modifier.height(10.dp))

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(items = edificios, key = { it.edificio.id }) { item ->
                CardEdificio(item = item, viewModel = viewModel)
            }
        }
    }
}

@Composable
fun CardEdificio(
    item: EdificioConAmbientesYNotas,
    viewModel: EdificiosViewModel
) {
    var editandoEdificio by remember { mutableStateOf(false) }
    var nuevoNombreEdificio by remember(item.edificio.nombre) { mutableStateOf(item.edificio.nombre) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (editandoEdificio) {
                    OutlinedTextField(
                        value = nuevoNombreEdificio,
                        onValueChange = { nuevoNombreEdificio = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    TextButton(
                        onClick = {
                            viewModel.actualizarEdificio(item.edificio.copy(nombre = nuevoNombreEdificio))
                            editandoEdificio = false
                        }
                    ) {
                        Text("Guardar", color = Naranja)
                    }
                } else {
                    Text(
                        text = item.edificio.nombre,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = RojoPrimario,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(5.dp))

            Text(
                text = "Categoría: ${item.edificio.categoria}",
                fontSize = 13.sp,
                color = TextoOscuro
            )

            Spacer(modifier = Modifier.height(5.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(if (item.edificio.actualizado) Verde else Naranja)
                )

                Spacer(modifier = Modifier.width(7.dp))

                Text(
                    text = if (item.edificio.actualizado) "Actualizado" else "Pendiente de sincronización",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (item.edificio.actualizado) Verde else Naranja
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = { editandoEdificio = !editandoEdificio }) {
                    Text(if (editandoEdificio) "Cancelar" else "Editar", color = Naranja)
                }

                TextButton(onClick = { viewModel.eliminarEdificio(item.edificio) }) {
                    Text("Borrar", color = RojoPrimario)
                }
            }

            HorizontalDivider(color = Amarillo.copy(alpha = 0.5f))

            Spacer(modifier = Modifier.height(8.dp))

            // RESTAURACIÓN DE LA VISTA DE AMBIENTES Y NOTAS
            item.ambientes.forEach { ambienteConNotas ->
                var editandoAmbiente by remember(ambienteConNotas.ambiente.id) { mutableStateOf(false) }
                var nuevoNombreAmbiente by remember(ambienteConNotas.ambiente.nombre) { mutableStateOf(ambienteConNotas.ambiente.nombre) }

                Column(modifier = Modifier.padding(start = 8.dp, top = 5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Naranja)
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        if (editandoAmbiente) {
                            OutlinedTextField(
                                value = nuevoNombreAmbiente,
                                onValueChange = { nuevoNombreAmbiente = it },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            TextButton(
                                onClick = {
                                    viewModel.actualizarAmbiente(
                                        ambienteConNotas.ambiente.copy(nombre = nuevoNombreAmbiente)
                                    )
                                    editandoAmbiente = false
                                }
                            ) {
                                Text("Guardar", color = Naranja)
                            }
                        } else {
                            Text(
                                text = ambienteConNotas.ambiente.nombre,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextoOscuro,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        TextButton(onClick = { editandoAmbiente = !editandoAmbiente }) {
                            Text(if (editandoAmbiente) "X" else "Editar", fontSize = 11.sp, color = Naranja)
                        }

                        TextButton(onClick = { viewModel.eliminarAmbiente(ambienteConNotas.ambiente) }) {
                            Text("Borrar", fontSize = 11.sp, color = RojoPrimario)
                        }
                    }

                    // NOTAS DEL AMBIENTE
                    ambienteConNotas.notas.forEach { nota ->
                        Box(
                            modifier = Modifier
                                .padding(start = 16.dp, top = 4.dp, bottom = 3.dp)
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFFFFFDE7))
                                .padding(10.dp)
                        ) {
                            Text(
                                text = "• ${nota.texto}",
                                fontSize = 12.sp,
                                color = Color(0xFF555555)
                            )
                        }
                    }
                }
            }
        }
    }
}