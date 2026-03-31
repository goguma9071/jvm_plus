# JVM PLUS (jvmplus)

`jvmplus`는 자바의 최신 Panama API(FFM)를 기반으로 하는 **초고성능 오프힙(Off-Heap) 데이터 구조 및 메모리 관리 라이브러리**입니다. C++의 정교한 메모리 제어와 자바의 타입 안전성을 결합하여, GC 부하 없는 시스템 프로그래밍 경험을 제공합니다.

---

## 1. 시작하기 (Getting Started)

### 구조체 정의
자바 인터페이스에 어노테이션을 붙여 오프힙 구조체를 정의합니다.

```java
@Struct.Type
public interface Player extends Struct {
    @Struct.Field(order = 1) int id(); 
    Player id(int v);

    @Struct.UTF8(length = 16) 
    @Struct.Field(order = 2) String name(); 
    Player name(String n);

    @Struct.Atomic 
    @Struct.Field(order = 3) int score(); 
    Player score(int v);
}
```

### 할당 및 해제
`JPhelper`의 문법 설탕을 사용하여 간결하게 코딩할 수 있습니다.

```java
import static com.github.goguma9071.jvmplus.JPhelper.*;

// 1. 수동 관리 (Manual)
Player p = alloc(Player.class);
p.id(7).name("Hero");
p.free(); // 명시적 해제

// 2. 자동 관리 (RAII - try-with-resources)
try (Player p2 = alloc(Player.class)) {
    p2.score(100);
} // 블록 종료 시 자동 해제
```

---

## 2. 구조체 시스템 (The Struct System)

### 필드 어노테이션 명세

| 어노테이션 | 설명 | 비고 |
| :--- | :--- | :--- |
| `@Struct.Type` | 인터페이스를 구조체로 지정 | `defaultLib` 설정 가능 |
| `@Struct.Field` | 일반 필드 정의 | `order` 필수, `offset` 수동 지정 가능 |
| `@Struct.BitField` | 비트 단위 데이터 저장 | 예: `bits = 1` (1비트 점유) |
| `@Struct.Atomic` | 원자적 연산 지원 | `VarHandle`의 CAS 연산 활용 |
| `@Struct.UTF8` | 오프힙 고정 길이 문자열 | `length` 바이트 수 지정 |
| `@Struct.Array` | 고정 길이 배열 필드 | `length` 지정 |
| `@Struct.Raw` | 원시 메모리 버퍼 (`RawBuffer`) | 바이너리 데이터 직접 조작 |
| `@Struct.Union` | C-Style 공용체 구현 | 모든 필드가 동일 오프셋 공유 |

---

## 3. 메모리 모델 (Memory Model)

### AoS vs SoA
*   **AoS (Array of Structs)**: 일반적인 구조체 배열. 캐시 지역성이 좋음.
    ```java
    StructArray<Player> players = array(Player.class, 1000);
    ```
*   **SoA (Structure of Arrays)**: 필드별로 별도 배열 관리. SIMD 최적화 및 특정 필드 스캔에 유리.
    ```java
    StructArray<Player> soaPlayers = allocSoA(Player.class, 1000);
    ```

### 할당자 (Allocators)
*   **`MemoryPool`**: 동일 크기 슬롯을 관리하는 Lock-free 풀. (기본값)
*   **`ArenaAllocator`**: FFM `Arena`를 직접 래핑.
*   **`BumpAllocator`**: 포인터 전진 방식의 극속 할당 (개별 해제 불가).
*   **`PoolAllocator`**: 특정 풀에서 메모리를 가져오는 전략.

---

## 4. 포인터 명세 (Pointers & Operations)

### `Pointer<T>`
강력한 타입 안전성을 가진 오프힙 포인터입니다.

```java
Pointer<Integer> p = ptr(10);
int value = p.deref(); // 값 읽기 (*)
p.set(20);             // 값 쓰기
System.out.println(p); // toString()으로 실제 값 출력 가능
```

### 포인터 연산 및 비교
C 언어 스타일의 포인터 산술 연산과 비교를 완벽하게 지원합니다.

```java
Pointer<Integer> p = ptr(100);

// 이동 (Pointer Arithmetic)
Pointer<Integer> next = add(p, 1); // ptr + 1
Pointer<Integer> prev = sub(p, 1); // ptr - 1

// 거리 및 비교 (Comparison)
long dist = next.distanceTo(p);    // ptr1 - ptr2 (결과: 1)
if (p.isBefore(next)) { ... }      // p < next
if (p.isNull()) { ... }            // p == NULL

// 이중 포인터 (Double Pointer)
Pointer<Pointer<Integer>> pp = pptr(p); // int**
```

---

## 5. 네이티브 상호운용성 (Native Interop)

### C 함수 호출
구조체 인터페이스에 직접 네이티브 함수를 바인딩할 수 있습니다.

```java
@NativeCall(name = "printf", lib = "libc.so.6")
int printf(String fmt, String msg);
```

### 콜백 (Upcalls)
자바 로직을 C 함수 포인터로 변환하여 전달합니다.

```java
MethodHandle cmp = fn(Main.class, "compare", int.class, MemorySegment.class, MemorySegment.class);
MemorySegment cb = callback(cmp, sig(JAVA_INT, ADDRESS, ADDRESS), arena);
```

---

## 6. 안전성 및 진단 (Safety & Diagnostics)

### 누수 탐지기 (Leak Detector)
프로그램 종료 시 해제되지 않은 모든 오프힙 메모리를 추적하여 스택 트레이스와 함께 리포트합니다.

```text
[JPC LEAK DETECTOR] WARNING: Memory leaks detected!
  > Addr: 0x7F6623E21010, Size: 32 bytes
    at com.github.goguma9071.jvmplus.Main.main(Main.java:77)
```

### 디버깅 도구
*   **`JPdebug.inspect(obj)`**: 메모리 레이아웃 및 현재 값을 시각적으로 출력합니다.
*   **`Struct.address()`**: 실제 물리 주소 확인.

---

## 7. 주요 API 단축키 (JPhelper Sugar)

| 메소드 | 설명 |
| :--- | :--- |
| `ptr(val)` | 기본 타입 포인터 할당 |
| `alloc(Class)` | 구조체 인스턴스 할당 |
| `scope()` | `Arena.ofConfined()` 단축 |
| `raw(size)` | 무타입 원시 버퍼 할당 |
| `vector(Class)` | 오프힙 가변 길이 리스트 |
| `hashmap(K, V)` | 오프힙 고성능 해시맵 |

---

## 8. 메모리 해제 규약
*   **`free()` (권장)**: 명시적이고 직관적인 메모리 해제.
*   **`close()` (호환용)**: `@Deprecated` 처리되어 있으나 `try-with-resources` 지원을 위해 내부적으로 유지됨.

---
**License**: MIT  
**Author**: goguma9071
