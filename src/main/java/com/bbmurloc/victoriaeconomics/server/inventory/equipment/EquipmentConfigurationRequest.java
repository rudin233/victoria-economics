package com.bbmurloc.victoriaeconomics.server.inventory.equipment;

import java.util.*;

/**
 * 设备配置请求的不可变状态，保留受理顺序、目标数量和真实执行结果。
 *
 * @param id       请求身份；重试时应复用原 ID
 * @param sequence 同栋建筑的统一受理顺序，安装和卸载共用该顺序
 * @param kind     安装或卸载
 * @param amount   申请处理的设备数量
 * @param executed 已真实执行的数量，部分执行时可以为零
 * @param status   请求当前状态；只有待执行请求可以取消，取消不反转已发生的设备变化
 */
public record EquipmentConfigurationRequest(UUID id, long sequence, Kind kind, int amount, int executed,
                                            Status status) {
    /**
     * 配置方向；两种操作都只转换设备形态，不改变总持有数量。
     */
    public enum Kind {INSTALL, UNINSTALL}

    /**
     * 待执行、全部执行、部分执行、已取消；后三者均不会在边界重试中再次执行。
     */
    public enum Status {PENDING, EXECUTED, PARTIAL, CANCELLED}

    public EquipmentConfigurationRequest {
        Objects.requireNonNull(id);
        Objects.requireNonNull(kind);
        Objects.requireNonNull(status);
        if (sequence <= 0 || amount <= 0 || executed < 0 || executed > amount)
            throw new IllegalArgumentException("Invalid equipment request");
        if ((status == Status.PENDING || status == Status.CANCELLED) && executed != 0)
            throw new IllegalArgumentException("Pending or cancelled request already executed");
        if (status == Status.EXECUTED && executed != amount)
            throw new IllegalArgumentException("Incomplete executed request");
        if (status == Status.PARTIAL && executed >= amount)
            throw new IllegalArgumentException("Partial request is complete");
    }
}
