package com.example.persistenciaroom_compose

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import com.example.persistenciaroom_compose.ui.theme.PersistenciaRoom_ComposeTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

val RojoPrimario = Color(0xFFD32F2F)
val NaranjaSecundario = Color(0xFFF57C00)
val NaranjaSuave = Color(0xFFFF9800)
val AmarilloDetalle = Color(0xFFFBC02D)
val AmarilloFondoNota = Color(0xFFFFFDE7)
val FondoPantalla = Color(0xFFFAFAFA)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val database = crearBaseDeDatos(applicationContext)
        val dao = database.edificioDao()
        val factory = EdificiosViewModelFactory(dao)
        setContent {
            PersistenciaRoom_ComposeTheme {
                val viewModel: EdificiosViewModel = viewModel(factory = factory)
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = FondoPantalla
                ) {
                    ListaEdificiosConAmbientes(viewModel)
                }
            }
        }
    }
}

@Entity(tableName = "EdificioEntity")
data class EdificioEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val nombre: String,
    val categoria: String
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
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
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
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val ambienteId: Int,
    val texto: String
)

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

    @Transaction
    @Query("SELECT * FROM AmbienteEntity WHERE id = :ambienteId")
    suspend fun obtenerAmbienteConNotas(ambienteId: Int): AmbienteConNotas?
}

@Database(
    entities = [EdificioEntity::class, AmbienteEntity::class, NotaEntity::class],
    version = 3
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun edificioDao(): EdificioDao
}

fun crearBaseDeDatos(context: Context): AppDatabase {
    return Room.databaseBuilder(
        context,
        AppDatabase::class.java,
        "app.db"
    )
        .fallbackToDestructiveMigration()
        .build()
}

class EdificiosViewModel(private val dao: EdificioDao) : ViewModel() {
    private val _edificios = MutableStateFlow<List<EdificioConAmbientesYNotas>>(emptyList())
    val edificios: StateFlow<List<EdificioConAmbientesYNotas>> = _edificios

    init {
        cargarDatos()
    }

    private fun cargarDatos() {
        viewModelScope.launch {
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
            _edificios.value = dao.obtenerConAmbientesYNotas()
        }
    }

    fun actualizarEdificio(edificio: EdificioEntity) {
        viewModelScope.launch {
            dao.actualizarEdificio(edificio)
            _edificios.value = dao.obtenerConAmbientesYNotas()
        }
    }

    fun eliminarEdificio(edificio: EdificioEntity) {
        viewModelScope.launch {
            dao.eliminarEdificio(edificio)
            _edificios.value = dao.obtenerConAmbientesYNotas()
        }
    }

    fun actualizarAmbiente(ambiente: AmbienteEntity) {
        viewModelScope.launch {
            dao.actualizarAmbiente(ambiente)
            _edificios.value = dao.obtenerConAmbientesYNotas()
        }
    }

    fun eliminarAmbiente(ambiente: AmbienteEntity) {
        viewModelScope.launch {
            dao.eliminarAmbiente(ambiente)
            _edificios.value = dao.obtenerConAmbientesYNotas()
        }
    }
}

class EdificiosViewModelFactory(private val dao: EdificioDao) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return EdificiosViewModel(dao) as T
    }
}

@Composable
fun ListaEdificiosConAmbientes(viewModel: EdificiosViewModel) {
    val edificios by viewModel.edificios.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "Gestión de Edificios",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = RojoPrimario
                ),
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
            )
        }

        items(edificios, key = { it.edificio.id }) { item ->
            CardEdificio(item, viewModel)
        }
    }
}

@Composable
fun CardEdificio(item: EdificioConAmbientesYNotas, viewModel: EdificiosViewModel) {
    var editandoEdificio by remember { mutableStateOf(false) }
    var nuevoNombreEdificio by remember { mutableStateOf(item.edificio.nombre) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (editandoEdificio) {
                    OutlinedTextField(
                        value = nuevoNombreEdificio,
                        onValueChange = { nuevoNombreEdificio = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    TextButton(onClick = {
                        viewModel.actualizarEdificio(item.edificio.copy(nombre = nuevoNombreEdificio))
                        editandoEdificio = false
                    }) {
                        Text("Guardar", color = NaranjaSecundario)
                    }
                } else {
                    Text(
                        text = item.edificio.nombre,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = RojoPrimario,
                        modifier = Modifier.weight(1f)
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(AmarilloDetalle.copy(alpha = 0.2f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = item.edificio.categoria,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = NaranjaSecundario
                    )
                }

                TextButton(onClick = { editandoEdificio = !editandoEdificio }) {
                    Text(if (editandoEdificio) "Cancelar" else "Editar", fontSize = 12.sp, color = NaranjaSecundario)
                }

                TextButton(onClick = { viewModel.eliminarEdificio(item.edificio) }) {
                    Text("Borrar", fontSize = 12.sp, color = RojoPrimario)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            item.ambientes.forEach { ambienteConNotas ->
                var editandoAmbiente by remember { mutableStateOf(false) }
                var nuevoNombreAmbiente by remember { mutableStateOf(ambienteConNotas.ambiente.nombre) }

                Column(modifier = Modifier.padding(start = 8.dp, top = 6.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(NaranjaSuave)
                        )
                        Spacer(modifier = Modifier.width(8.dp))

                        if (editandoAmbiente) {
                            OutlinedTextField(
                                value = nuevoNombreAmbiente,
                                onValueChange = { nuevoNombreAmbiente = it },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            TextButton(onClick = {
                                viewModel.actualizarAmbiente(ambienteConNotas.ambiente.copy(nombre = nuevoNombreAmbiente))
                                editandoAmbiente = false
                            }) {
                                Text("Guardar", color = NaranjaSecundario)
                            }
                        } else {
                            Text(
                                text = ambienteConNotas.ambiente.nombre,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFF333333),
                                modifier = Modifier.weight(1f)
                            )
                        }

                        TextButton(onClick = { editandoAmbiente = !editandoAmbiente }) {
                            Text(if (editandoAmbiente) "X" else "Editar", fontSize = 11.sp, color = NaranjaSecundario)
                        }

                        TextButton(onClick = { viewModel.eliminarAmbiente(ambienteConNotas.ambiente) }) {
                            Text("Borrar", fontSize = 11.sp, color = RojoPrimario)
                        }
                    }

                    ambienteConNotas.notas.forEach { nota ->
                        Box(
                            modifier = Modifier
                                .padding(start = 16.dp, top = 4.dp, bottom = 2.dp)
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(AmarilloFondoNota)
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "• ${nota.texto}",
                                fontSize = 13.sp,
                                color = Color(0xFF555555)
                            )
                        }
                    }
                }
            }
        }
    }
}