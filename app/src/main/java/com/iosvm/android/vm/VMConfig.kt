package com.iosvm.android.vm

/**
 * VMConfig — Configuración completa de una máquina virtual
 */
data class VMConfig(
    val name: String = "iOS VM",
    val diskImagePath: String,
    val ramMB: Int = 2048,          // 2GB por defecto
    val cpuCores: Int = 2,
    val vncPort: Int = 5900,
    val extraArgs: String = "",
    val architecture: Arch = Arch.AARCH64,
    val machine: Machine = Machine.VIRT
) {
    enum class Arch(val qemuTarget: String) {
        AARCH64("aarch64-softmmu"),
        X86_64("x86_64-softmmu")
    }

    enum class Machine(val qemuMachine: String) {
        VIRT("virt,highmem=off"),       // Para ARM/iOS
        PC("pc"),                        // Para x86
        Q35("q35")                       // Para x86 moderno
    }

    companion object {
        /** Configuración optimizada para iOS Simulator */
        fun forIOS(diskPath: String, ramMB: Int = 2048) = VMConfig(
            name         = "iOS VM",
            diskImagePath = diskPath,
            ramMB        = ramMB,
            architecture = Arch.AARCH64,
            machine      = Machine.VIRT,
            extraArgs    = "-cpu max -smp 2"
        )

        /** Configuración para macOS ARM */
        fun forMacOS(diskPath: String, ramMB: Int = 4096) = VMConfig(
            name          = "macOS VM",
            diskImagePath = diskPath,
            ramMB         = ramMB,
            architecture  = Arch.AARCH64,
            machine       = Machine.VIRT,
            extraArgs     = "-cpu host -smp 4 -device virtio-gpu-pci"
        )

        /** Configuración para Linux ARM (pruebas sin imagen Apple) */
        fun forLinuxARM(diskPath: String) = VMConfig(
            name          = "Linux ARM64",
            diskImagePath = diskPath,
            ramMB         = 1024,
            architecture  = Arch.AARCH64,
            machine       = Machine.VIRT,
            extraArgs     = "-kernel /data/vm/vmlinuz -initrd /data/vm/initrd.img"
        )
    }
}
