package cheeezer.notenoughspectators;

import com.google.common.collect.Queues;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.PositionMoveRotation;

import java.util.Queue;
import java.util.function.Consumer;

public class PlayerTaskQueue {
    private static final Queue<Consumer<LocalPlayer>> TASK_QUEUE = Queues.newConcurrentLinkedQueue();
    private static final Queue<Consumer<PositionMoveRotation>> POSITION_TASK_QUEUE = Queues.newConcurrentLinkedQueue();

    public static void addTask(Consumer<LocalPlayer> task) {
        TASK_QUEUE.add(task);
    }

    public static void addPositionTask(Consumer<PositionMoveRotation> task) {
        POSITION_TASK_QUEUE.add(task);
    }

    public static void processTasks(LocalPlayer player) {
        while (!TASK_QUEUE.isEmpty()) {
            Consumer<LocalPlayer> task = TASK_QUEUE.poll();
            if (task != null) {
                task.accept(player);
            }
        }
    }

    public static void processPositionTasks(PositionMoveRotation position) {
        while (!POSITION_TASK_QUEUE.isEmpty()) {
            Consumer<PositionMoveRotation> task = POSITION_TASK_QUEUE.poll();
            if (task != null) {
                task.accept(position);
            }
        }
    }
}
