# Отчёт по домашнему заданию № 7

**Дисциплина:** Теория и практика многопоточности  
**Тема:** Deadlock, livelock, starvation

## 1. Подготовка и структура проекта

`DeadlockDemo.java` моделирует взаимную блокировку и исправление. `Demos.java` содержит livelock, starvation и исправления. `Main.java` позволяет запускать каждый сценарий отдельно. Режим `all` выполняет конечные демонстрации; режим `deadlock` действительно зависает до остановки JVM.

## 2. Реализация Deadlock

Созданы ресурсы `resourceA` и `resourceB` и потоки `deadlock-1`, `deadlock-2`:

```java
// Поток 1
synchronized (resourceA) {
    rendezvous(bothOwnFirst);
    synchronized (resourceB) { /* работа */ }
}
// Поток 2
synchronized (resourceB) {
    rendezvous(bothOwnFirst);
    synchronized (resourceA) { /* работа */ }
}
```

`CountDownLatch(2)` гарантирует, что оба потока захватят первый ресурс до попытки получить второй. Когда барьер пройден, первый удерживает A и ждёт B; второй удерживает B и ждёт A. Никто не может выйти из внешнего `synchronized`, поэтому образуется цикл ожидания. Дополнительная проверка через `ThreadMXBean.findDeadlockedThreads()` подтверждает взаимную блокировку, а main остаётся в `join()`.

Выполняются четыре условия: ресурсы эксклюзивны; поток удерживает один ресурс и ждёт другой; ресурс нельзя принудительно забрать; существует цикл ожидания. Программа не просто работает медленно — полезное выполнение обоих потоков остановлено.

## 3. Диагностика с помощью jstack

Выполнен `capture.cmd`: он запустил отдельную JVM, дождался обнаружения deadlock и выполнил **`jstack -l 30304`**. После сохранения дампа остановлен только созданный процесс PID 30304. Этот PID относится к фактическому запуску; при повторении будет другим. `-l` включает дополнительную информацию о блокировках. Назначение команды описано в [официальной документации jstack](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jstack.html).

Полный дамп сохранён в [evidence/deadlock-jstack.txt](evidence/deadlock-jstack.txt), вывод процесса — в [evidence/deadlock-run.txt](evidence/deadlock-run.txt). Ниже приведены диагностические строки фактического дампа; служебные кадры стека опущены.

```text
"deadlock-1" #27 ...
   java.lang.Thread.State: BLOCKED (on object monitor)
    at ru.homework.concurrency.DeadlockDemo.lambda$start$0(DeadlockDemo.java:21)
    - waiting to lock <0x0000000701875fb0> (a ru.homework.concurrency.DeadlockDemo$ResourceB)
    - locked <0x0000000701875378> (a ru.homework.concurrency.DeadlockDemo$ResourceA)

"deadlock-2" #28 ...
   java.lang.Thread.State: BLOCKED (on object monitor)
    at ru.homework.concurrency.DeadlockDemo.lambda$start$1(DeadlockDemo.java:28)
    - waiting to lock <0x0000000701875378> (a ru.homework.concurrency.DeadlockDemo$ResourceA)
    - locked <0x0000000701875fb0> (a ru.homework.concurrency.DeadlockDemo$ResourceB)

Found one Java-level deadlock:
=============================
"deadlock-1":
  waiting to lock monitor 0x00000192b4b245c0 (object 0x0000000701875fb0, a ru.homework.concurrency.DeadlockDemo$ResourceB),
  which is held by "deadlock-2"

"deadlock-2":
  waiting to lock monitor 0x00000192b4b16500 (object 0x0000000701875378, a ru.homework.concurrency.DeadlockDemo$ResourceA),
  which is held by "deadlock-1"

Found 1 deadlock.
```

| Поток      | Удерживает                      | Ожидает                         | Состояние |
| ---------- | ------------------------------- | ------------------------------- | --------- |
| deadlock-1 | resourceA: `0x0000000701875378` | resourceB: `0x0000000701875fb0` | BLOCKED   |
| deadlock-2 | resourceB: `0x0000000701875fb0` | resourceA: `0x0000000701875378` | BLOCKED   |

Одинаковые адреса в строках `locked` одного потока и `waiting to lock` другого доказывают ожидание именно этих двух объектов. Цикл: **поток 1 → B → поток 2 → A → поток 1**. В итоговом разделе JVM явно сообщает о Java-level deadlock. Состояние main, ожидающего завершения рабочих потоков, является следствием, а не причиной проблемы. Адреса объектов и номера потоков относятся только к этому запуску.

Для самостоятельной диагностики: запустить `run.cmd deadlock`, взять PID из вывода, в другом терминале выполнить `jstack -l <текущий PID>`. На Windows применён `jstack`; вариант `kill -3` предназначен для Unix-подобных систем.

## 4. Моделирование Livelock

Два потока захватывают разные `ReentrantLock`, пытаются получить второй через `tryLock()`, при отказе освобождают первый и повторяют попытку. Барьеры синхронизируют этапы: оба удерживают первый ресурс во время попытки получить второй, оба получают отказ, затем оба освобождают ресурсы и повторяют те же действия.

Наблюдение ограничено 100 раундами. Получен вывод:

```text
LIVELOCK: rounds=100 attempts=200 completed=0; stopped by observation limit
```

Потоки выполняют новые попытки, освобождают и захватывают ресурсы, но не завершают ни одной полезной операции. Это отличается от deadlock: ресурсы освобождаются, цикл повторных действий продолжается. Служебные ожидания на барьерах допустимы: критерий livelock — активность алгоритма без прогресса, а не непрерывное состояние RUNNABLE каждого потока. Лимит наблюдения позволяет закончить демонстрацию; он не делает неправильную стратегию успешной. Без лимита синхронные повторные попытки продолжались бы.

## 5. Моделирование Starvation

Реализован диспетчер ресурса с политикой строгого приоритета. Низкоприоритетный запрос ждёт на `Condition`, пока существует высокоприоритетный backlog; высокоприоритетный поток продолжает получать ресурс. После 1000 его операций выполнение производителя приостановлено для снимка, но флаг очереди приоритетных запросов остаётся установленным.

```text
STARVATION: highCompleted=1000 lowCompleted=0 lowState=WAITING
STARVATION cleanup: backlog removed, lowCompleted=1; both workers terminated
```

Приоритетный участник делает полезную работу, а другой участник исключён из доступа: это starvation. Наблюдение конечное; бесконечное голодание не измерялось. Из условия допуска следует, что при непрерывной приоритетной очереди низкий запрос мог бы ждать неограниченно. В конце демонстрации поток приоритетных задач остановлен, backlog снят, ожидающий участник выполнил операцию и оба потока завершились.

Использована явная политика допуска, а не только `Thread.setPriority`: поведение приоритетов зависит от планировщика и не является надёжным способом воспроизводить голодание на любой системе.

## 6. Предотвращение проблем и корректные примеры

**Deadlock:** оба потока всегда захватывают A, затем B. Общий порядок устраняет цикл ожидания. Проверено выполнение 1000 операций каждым потоком:

```java
synchronized (resourceA) {
    synchronized (resourceB) { operations[0]++; }
}
```

```text
DEADLOCK FIX: completed=2000; both workers terminated
```

**Livelock:** симметричный конфликт устранён общим порядком ресурсов. `tryLock(50, TimeUnit.MILLISECONDS)` ограничивает каждое ожидание; общий deadline 5 секунд ограничивает повторные попытки. Захваченные ресурсы освобождаются в `finally`. Если времени недостаточно, возникает явная ошибка, а не бесконечный цикл.

```java
if (!resourceA.tryLock(50, TimeUnit.MILLISECONDS)) continue;
try {
    if (!resourceB.tryLock(50, TimeUnit.MILLISECONDS)) continue;
    try { completed.incrementAndGet(); ownCompleted++; }
    finally { resourceB.unlock(); }
} finally { resourceA.unlock(); }
```

```text
LIVELOCK FIX: completed=200; ordered timed locks, total deadline=5s
```

Один таймаут с немедленным симметричным повтором не гарантирует прогресса. Поэтому здесь сочетаются общий порядок, ограниченное ожидание и общий предел времени. В иных алгоритмах можно также применять случайную задержку повторов или выбор одного ведущего участника.

**Starvation:** вместо приоритетного допуска используется `ReentrantLock(true)`, все потоки имеют `NORM_PRIORITY`, захват выполняется через `lockInterruptibly()` и освобождение — в `finally`. Критическая секция короткая, объём работы каждого участника ограничен. Четыре потока завершили по 300 операций:

```java
ReentrantLock fairResource = new ReentrantLock(true);
fairResource.lockInterruptibly();
try { counts[index]++; }
finally { fairResource.unlock(); }
```

```text
STARVATION FIX: fair lock, equal priorities, per-worker=[300, 300, 300, 300]
```

Fair lock отдаёт предпочтение дольше ожидающим претендентам, но не обеспечивает справедливость планирования ОС; обычный `tryLock()` без таймаута обходит настройку fairness. Поэтому в исправлении starvation используется `lockInterruptibly()`. Эти свойства описаны в [документации ReentrantLock](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/locks/ReentrantLock.html).

`Thread.yield()` — подсказка, которую планировщик вправе игнорировать; она не используется здесь как гарантия равномерного доступа. Это прямо указано в [документации Thread.yield](<https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Thread.html#yield()>).
