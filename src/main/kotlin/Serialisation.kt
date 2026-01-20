import org.apache.commons.csv.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties

fun <T : Any> KClass<T>.exportCSV(values: Iterable<T>, path: Path) {
    val properties = memberProperties
    val format = CSVFormat.DEFAULT.builder()
        .setHeader(*properties.map { it.name }.toTypedArray())
        .build()

    Files.newBufferedWriter(path).use { writer ->
        CSVPrinter(writer, format)
            .use { csv ->
                for (value in values) {
                    csv.printRecord(
                        properties.map { property ->
                            when (val propertyValue = property.get(value)) {
                                is List<*> -> propertyValue.joinToString(";")
                                null -> ""
                                else -> propertyValue
                            }
                        }
                    )
                }
            }
    }
}

fun <T> loadCSV(path: Path, action: (CSVRecord) -> T): List<T> {
    Files.newBufferedReader(path)
        .use { reader ->
            val parser = CSVParser(
                reader,
                CSVFormat.DEFAULT
                    .builder()
                    .setHeader()
                    .setSkipHeaderRecord(true)
                    .build()
            )

            return parser.records.map(action)
        }
}