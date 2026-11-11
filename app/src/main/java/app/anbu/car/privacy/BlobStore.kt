package app.anbu.car.privacy

/**
 * 차 안 저장소. 이름으로 문자열 한 덩어리를 읽고 쓴다.
 * 실제 구현(KeystoreBlobStore)은 Android Keystore 키로 암호화해 파일에 둔다.
 */
interface BlobStore {
    fun read(name: String): String?
    fun write(name: String, value: String)
    fun delete(name: String)

    /** 저장된 것을 모두 지운다. 암호화 구현은 키도 함께 없앤다. */
    fun wipe()
}

/** 테스트와 미리보기용. */
class InMemoryBlobStore : BlobStore {
    private val map = HashMap<String, String>()
    val names: Set<String> get() = map.keys.toSet()
    override fun read(name: String): String? = map[name]
    override fun write(name: String, value: String) { map[name] = value }
    override fun delete(name: String) { map.remove(name) }
    override fun wipe() { map.clear() }
}
