package com.wmp.windowmask;

import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Win32 窗口信息读取与窗口层级调整（仅供「窗口遮挡」内部使用）。
 *
 * <p>零第三方依赖：使用 JDK 25 已转正的 FFM API（{@code java.lang.foreign}）直接调用 {@code user32.dll}，
 * 与主程序的 {@code com.wmp.downloader.tools.ui.WindowBackdrop} 采用同一套做法，不需要 JNA。</p>
 *
 * <p>能力分三块：</p>
 * <ol>
 *     <li>读取窗口信息：{@code GetForegroundWindow} / {@code GetWindowTextW} / {@code GetWindowRect} /
 *         {@code IsWindowVisible} / {@code IsIconic} / {@code GetWindowThreadProcessId}；</li>
 *     <li>枚举全机可见顶层窗口（{@code EnumWindows}），用于“不管有没有焦点，都按标题匹配”；</li>
 *     <li>调整窗口层级（{@code SetWindowPos} + {@code GetWindow}），把遮罩放到目标窗口<strong>之上</strong>。</li>
 * </ol>
 *
 * <h3>关于 z 序（这里最容易搞错）</h3>
 * <p>{@code SetWindowPos(hWnd, hWndInsertAfter, ...)} 的 {@code hWndInsertAfter} 含义是
 * “<b>位于被定位窗口之上</b>的窗口”。所以要把遮罩放到目标的上面，必须传
 * <b>目标窗口正上方那个窗口</b>（{@code GetWindow(target, GW_HWNDPREV)}）作为
 * {@code hWndInsertAfter}；直接传目标句柄反而会把遮罩塞到目标<strong>下面</strong>。
 * 目标已是最顶层时改用 {@code HWND_TOP}；目标本身是置顶窗口时遮罩也必须置顶。
 * 放置完成后还会用 {@link #isAbove} 复核，失败则调用方可以退化为置顶显示。</p>
 *
 * <p>所有方法在非 Windows 或原生库不可用时都安全降级（{@link #available} 为 {@code false}，
 * 返回空值/0/{@code false}），调用方无需自行判空。</p>
 *
 * <p>注意：使用 FFM 需要启动参数 {@code --enable-native-access=ALL-UNNAMED}，不加只会打印警告，不影响功能。</p>
 *
 * @author 无名牌
 */
final class WinNative {

    /**
     * 原生调用是否可用（Windows 且 user32.dll 可用）。
     * 为 {@code false} 时本插件的业务逻辑会直接跳过，不产生任何遮挡。
     */
    static final boolean available;

    /** 本进程 PID：用于排除减速带自己的窗口（含遮罩层自身） */
    private static final long OWN_PID = ProcessHandle.current().pid();

    /** 共享 Arena：句柄在插件整个生命周期内使用，不能是 confined 作用域 */
    private static final Arena ARENA = Arena.ofShared();

    /** 窗口标题缓冲区容量（UTF-16 字符数） */
    private static final int TITLE_CAPACITY = 512;

    /** 枚举回调使用的共享标题缓冲区（枚举是同步单线程过程，可安全复用） */
    private static final MemorySegment TITLE_BUFFER = ARENA.allocate((long) TITLE_CAPACITY * 2);

    /** EnumWindows 的收集结果 */
    private static final List<WindowEntry> ENUM_RESULT = new ArrayList<>();

    // ---- SetWindowPos / GetWindow 常量 ----
    private static final int SWP_NOSIZE = 0x0001;
    private static final int SWP_NOMOVE = 0x0002;
    private static final int SWP_NOACTIVATE = 0x0010;
    private static final int SWP_NOOWNERZORDER = 0x0200;
    private static final int SWP_FLAGS = SWP_NOSIZE | SWP_NOMOVE | SWP_NOACTIVATE | SWP_NOOWNERZORDER;
    /** GetWindow 的 GW_HWNDPREV：取 z 序中位于该窗口<strong>之上</strong>的窗口 */
    private static final int GW_HWNDPREV = 3;
    private static final int GWL_EXSTYLE = -20;
    private static final long WS_EX_TOPMOST = 0x00000008L;
    /** SetWindowPos 特殊插入位置 */
    private static final long HWND_TOP = 0L;
    private static final long HWND_TOPMOST = -1L;
    private static final long HWND_NOTOPMOST = -2L;
    /** 自身窗口矩形匹配容差（物理像素） */
    private static final int RECT_MATCH_TOLERANCE = 48;

    private static MethodHandle getForegroundWindow;
    private static MethodHandle getWindowTextW;
    private static MethodHandle getWindowRect;
    private static MethodHandle isWindowVisible;
    private static MethodHandle isIconic;
    private static MethodHandle isWindow;
    private static MethodHandle getWindowThreadProcessId;
    private static MethodHandle enumWindows;
    private static MethodHandle setWindowPos;
    private static MethodHandle getWindow;
    /** 可选：某些环境可能没有该符号，取不到时按“非置顶窗口”处理 */
    private static MethodHandle getWindowLongPtrW;
    private static MemorySegment enumCallback;

    static {
        boolean ok = false;
        try {
            if (isWindows() && !GraphicsEnvironment.isHeadless()) {
                Linker linker = Linker.nativeLinker();
                SymbolLookup user32 = SymbolLookup.libraryLookup("user32.dll", ARENA);

                getForegroundWindow = linker.downcallHandle(
                        user32.find("GetForegroundWindow").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.ADDRESS));
                getWindowTextW = linker.downcallHandle(
                        user32.find("GetWindowTextW").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
                getWindowRect = linker.downcallHandle(
                        user32.find("GetWindowRect").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                isWindowVisible = linker.downcallHandle(
                        user32.find("IsWindowVisible").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
                isIconic = linker.downcallHandle(
                        user32.find("IsIconic").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
                isWindow = linker.downcallHandle(
                        user32.find("IsWindow").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
                getWindowThreadProcessId = linker.downcallHandle(
                        user32.find("GetWindowThreadProcessId").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                enumWindows = linker.downcallHandle(
                        user32.find("EnumWindows").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                setWindowPos = linker.downcallHandle(
                        user32.find("SetWindowPos").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
                getWindow = linker.downcallHandle(
                        user32.find("GetWindow").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
                getWindowLongPtrW = user32.find("GetWindowLongPtrW")
                        .map(address -> linker.downcallHandle(address,
                                FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT)))
                        .orElse(null);

                enumCallback = linker.upcallStub(
                        MethodHandles.lookup().findStatic(WinNative.class, "enumProc",
                                MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class)),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                        ARENA);
                ok = true;
            }
        } catch (Throwable t) {
            // 非 Windows、缺少 user32.dll 或 native access 被拒绝：静默降级
            ok = false;
        }
        available = ok;
    }

    private WinNative() {
    }

    /** 一个顶层窗口的句柄与标题 */
    record WindowEntry(long hwnd, String title) {
    }

    /** 当前系统是否为 Windows */
    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("windows");
    }

    // ==================================================================
    // 窗口信息
    // ==================================================================

    /**
     * 取当前前台窗口（获得焦点的窗口）句柄。
     *
     * @return 窗口句柄；取不到返回 {@code 0}
     */
    static long foregroundWindow() {
        if (!available) {
            return 0;
        }
        try {
            MemorySegment hwnd = (MemorySegment) getForegroundWindow.invoke();
            return hwnd == null ? 0L : hwnd.address();
        } catch (Throwable t) {
            return 0L;
        }
    }

    /**
     * 取窗口标题。
     *
     * <p>说明：{@code GetWindowTextW} 写出的是 UTF-16LE，必须按 UTF-16LE 解码（不能按 UTF-8）。</p>
     *
     * @param hwnd 窗口句柄
     * @return 窗口标题；取不到返回空串
     */
    static String windowTitle(long hwnd) {
        if (!available || hwnd == 0L) {
            return "";
        }
        try (Arena arena = Arena.ofConfined()) {
            return readTitleInto(arena.allocate((long) TITLE_CAPACITY * 2), hwnd);
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 取窗口矩形，单位是<strong>物理像素</strong>（调用方需按界面缩放换算为 Swing 逻辑坐标）。
     *
     * @param hwnd 窗口句柄
     * @return 窗口矩形；取不到返回 {@code null}
     */
    static Rectangle windowRect(long hwnd) {
        if (!available || hwnd == 0L) {
            return null;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment rect = arena.allocate(16);
            if ((int) getWindowRect.invoke(MemorySegment.ofAddress(hwnd), rect) == 0) {
                return null;
            }
            int left = rect.get(ValueLayout.JAVA_INT, 0);
            int top = rect.get(ValueLayout.JAVA_INT, 4);
            int right = rect.get(ValueLayout.JAVA_INT, 8);
            int bottom = rect.get(ValueLayout.JAVA_INT, 12);
            return new Rectangle(left, top, right - left, bottom - top);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 窗口是否可见 */
    static boolean isVisible(long hwnd) {
        if (!available || hwnd == 0L) {
            return false;
        }
        try {
            return (int) isWindowVisible.invoke(MemorySegment.ofAddress(hwnd)) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 窗口是否已最小化（最小化时矩形不可信，不应遮挡） */
    static boolean isMinimized(long hwnd) {
        if (!available || hwnd == 0L) {
            return true;
        }
        try {
            return (int) isIconic.invoke(MemorySegment.ofAddress(hwnd)) != 0;
        } catch (Throwable t) {
            return true;
        }
    }

    /** 句柄当前是否仍是一个有效窗口 */
    static boolean isWindow(long hwnd) {
        if (!available || hwnd == 0L) {
            return false;
        }
        try {
            return (int) isWindow.invoke(MemorySegment.ofAddress(hwnd)) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 取窗口所属进程的 PID。
     *
     * <p>用于排除减速带自己的窗口（插件宿主与遮罩层自身）。</p>
     *
     * @return 进程 PID；取不到返回 {@code -1}
     */
    static long windowProcessId(long hwnd) {
        if (!available || hwnd == 0L) {
            return -1L;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pid = arena.allocate(4);
            getWindowThreadProcessId.invoke(MemorySegment.ofAddress(hwnd), pid);
            return Integer.toUnsignedLong(pid.get(ValueLayout.JAVA_INT, 0));
        } catch (Throwable t) {
            return -1L;
        }
    }

    /** 该窗口是否属于减速带自身进程 */
    static boolean isOwnWindow(long hwnd) {
        return hwnd != 0L && windowProcessId(hwnd) == OWN_PID;
    }

    // ==================================================================
    // 枚举所有顶层窗口
    // ==================================================================

    /**
     * 枚举全机所有<strong>可见的顶层窗口</strong>（各进程都包括），按 z 序自上而下。
     *
     * <p>这是“不依赖焦点”的关键：不看谁在前台，只看哪些窗口存在且标题匹配。</p>
     *
     * @return 可见顶层窗口列表；不可用时返回空列表
     */
    static synchronized List<WindowEntry> listVisibleWindows() {
        List<WindowEntry> result = new ArrayList<>();
        if (!available) {
            return result;
        }
        ENUM_RESULT.clear();
        try {
            enumWindows.invoke(enumCallback, MemorySegment.NULL);
        } catch (Throwable t) {
            ENUM_RESULT.clear();
            return result;
        }
        result.addAll(ENUM_RESULT);
        return result;
    }

    /** EnumWindows 回调：收集可见顶层窗口的句柄与标题 */
    private static int enumProc(MemorySegment hwnd, MemorySegment lParam) {
        try {
            if ((int) isWindowVisible.invoke(hwnd) != 0) {
                long handle = hwnd.address();
                ENUM_RESULT.add(new WindowEntry(handle, readTitleInto(TITLE_BUFFER, handle)));
            }
        } catch (Throwable ignored) {
            // 单个窗口查询失败不影响整体枚举
        }
        return 1;
    }

    // ==================================================================
    // 窗口层级
    // ==================================================================

    /**
     * 把遮罩窗口放到目标窗口的<strong>正上方</strong>（z 序紧邻），且不激活、不改变位置尺寸。
     *
     * <p>这样后台窗口的遮罩只出现在那个窗口前面，不会浮到最顶层挡住用户正在使用的窗口；
     * 每次检测都会重新调用，用于跟随窗口的激活/置顶变化。</p>
     *
     * @param overlayHwnd 遮罩窗口句柄
     * @param targetHwnd  目标窗口句柄
     * @return 是否确实放到了目标窗口之上（已用 {@link #isAbove} 复核）
     */
    static boolean placeAboveTarget(long overlayHwnd, long targetHwnd) {
        if (!available || overlayHwnd == 0L || targetHwnd == 0L) {
            return false;
        }
        long insertAfter;
        if (isTopMost(targetHwnd)) {
            // 目标本身是置顶窗口（部分播放器/游戏），遮罩也必须置顶才盖得住
            insertAfter = HWND_TOPMOST;
        } else {
            // 关键：要放到目标“上面”，传的必须是目标正上方那个窗口；目标已是最顶层时用 HWND_TOP
            long aboveTarget = windowAbove(targetHwnd);
            insertAfter = (aboveTarget == 0L) ? HWND_TOP : aboveTarget;
        }
        if (!applyZOrder(overlayHwnd, insertAfter)) {
            return false;
        }
        return isAbove(overlayHwnd, targetHwnd);
    }

    /** 立即把遮罩置顶（层级放置失败时的兜底，保证遮挡一定看得见） */
    static boolean setTopMost(long hwnd) {
        return applyZOrder(hwnd, HWND_TOPMOST);
    }

    /** 取消置顶，恢复普通层级（兜底结束后调用，让遮罩重新紧跟目标窗口） */
    static boolean clearTopMost(long hwnd) {
        return applyZOrder(hwnd, HWND_NOTOPMOST);
    }

    /** 窗口是否带 {@code WS_EX_TOPMOST} 置顶属性 */
    static boolean isTopMost(long hwnd) {
        if (!available || hwnd == 0L || getWindowLongPtrW == null) {
            return false;
        }
        try {
            long exStyle = (long) getWindowLongPtrW.invoke(MemorySegment.ofAddress(hwnd), GWL_EXSTYLE);
            return (exStyle & WS_EX_TOPMOST) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * {@code a} 是否在 z 序中位于 {@code b} 之上。
     *
     * <p>做法是从 {@code a} 沿着“正上方”逐级向上遍历：途中先遇到 {@code b} 就说明 {@code a} 在
     * {@code b} 下面；一路走到顶端都没遇到，就说明 {@code a} 在 {@code b} 上面。</p>
     */
    static boolean isAbove(long a, long b) {
        if (!available || a == 0L || b == 0L || a == b) {
            return false;
        }
        long current = a;
        for (int i = 0; i < 256; i++) {
            long prev = windowAbove(current);
            if (prev == 0L) {
                return true;
            }
            if (prev == b) {
                return false;
            }
            current = prev;
        }
        return true;
    }

    /** 取 z 序中位于该窗口之上的窗口句柄；已是最顶层时返回 {@code 0} */
    static long windowAbove(long hwnd) {
        if (!available || hwnd == 0L) {
            return 0L;
        }
        try {
            MemorySegment above = (MemorySegment) getWindow.invoke(MemorySegment.ofAddress(hwnd), GW_HWNDPREV);
            return above == null ? 0L : above.address();
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static boolean applyZOrder(long hwnd, long insertAfter) {
        try {
            return (int) setWindowPos.invoke(MemorySegment.ofAddress(hwnd),
                    MemorySegment.ofAddress(insertAfter), 0, 0, 0, 0, SWP_FLAGS) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 按矩形在<strong>本进程</strong>的“无标题可见窗口”中寻找遮罩层自身的 HWND。
     *
     * <p>JDK 25 已移除 {@code Component.getPeer()}，无法直接拿到窗口句柄，因此改用
     * “枚举本进程窗口 + 无标题（AWT 的 Frame 有标题，JWindow 没有）+ 矩形最接近”来定位。</p>
     *
     * @param logicalBounds Swing 逻辑坐标下的遮罩矩形（即 {@code JWindow.getBounds()}）
     * @return 窗口句柄；找不到返回 {@code 0}
     */
    static long findOwnTitlelessWindow(Rectangle logicalBounds) {
        if (!available || logicalBounds == null) {
            return 0L;
        }
        Rectangle expected = ScreenScale.toPhysical(logicalBounds);
        long best = 0L;
        int bestDelta = Integer.MAX_VALUE;
        for (WindowEntry entry : listVisibleWindows()) {
            if (!entry.title().isEmpty()) {
                continue;
            }
            if (!isOwnWindow(entry.hwnd())) {
                continue;
            }
            Rectangle rect = windowRect(entry.hwnd());
            if (rect == null) {
                continue;
            }
            int delta = Math.abs(rect.x - expected.x) + Math.abs(rect.y - expected.y)
                    + Math.abs(rect.width - expected.width) + Math.abs(rect.height - expected.height);
            if (delta < bestDelta) {
                bestDelta = delta;
                best = entry.hwnd();
            }
        }
        return bestDelta <= RECT_MATCH_TOLERANCE ? best : 0L;
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    /** 读取窗口标题到指定缓冲区（缓冲区容量为 {@link #TITLE_CAPACITY} 个 UTF-16 字符） */
    private static String readTitleInto(MemorySegment buffer, long hwnd) {
        try {
            int length = (int) getWindowTextW.invoke(MemorySegment.ofAddress(hwnd), buffer, TITLE_CAPACITY);
            if (length <= 0) {
                return "";
            }
            // GetWindowTextW 写出 UTF-16LE，不能按 UTF-8 解码
            byte[] raw = buffer.asSlice(0, (long) length * 2).toArray(ValueLayout.JAVA_BYTE);
            return new String(raw, StandardCharsets.UTF_16LE);
        } catch (Throwable t) {
            return "";
        }
    }
}
