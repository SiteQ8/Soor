package com.eworldq8.soor.scan

enum class DeviceKind { ROUTER, CAMERA, TV, NAS, COMPUTER, PHONE, PRINTER, IOT, UNKNOWN }

/**
 * A best guess at what a device is, from the ports it opens and the name it
 * gives itself over UPnP. Only a guess, so it never changes a verdict: the
 * engine judges services, and this only picks the picture and the label.
 */
object DeviceKinds {
    private val CAMERA_PORTS = setOf(554, 8554, 34567, 37777, 2020, 8899)

    fun guess(ports: Set<Int>, ssdp: SsdpDevice?, isGateway: Boolean, cameraVendor: Boolean, hint: String = ""): DeviceKind {
        val text = listOfNotNull(hint, ssdp?.friendlyName, ssdp?.manufacturer, ssdp?.modelName, ssdp?.deviceType, ssdp?.server)
            .joinToString(" ").lowercase()
        fun has(vararg words: String) = words.any { text.contains(it) }
        return when {
            isGateway || has("internetgatewaydevice", "router") -> DeviceKind.ROUTER
            has("_companion-link", "_rdlink") && !has("macbook", "imac", "mac mini") -> DeviceKind.PHONE
            has("_airplay", "_raop", "_googlecast", "apple tv") -> DeviceKind.TV
            has("_ipp", "_printer") -> DeviceKind.PRINTER
            has("_smb", "_ssh", "_workstation", "macbook", "imac", "desktop-", "laptop") -> DeviceKind.COMPUTER
            has("_hap", "_sonos", "_spotify") -> DeviceKind.IOT
            cameraVendor || ports.any { it in CAMERA_PORTS } || has("camera", "ipcam", "nvr", "dvr") -> DeviceKind.CAMERA
            ports.any { it in setOf(9100, 631, 515) } || has("printer", "laserjet", "deskjet", "officejet") -> DeviceKind.PRINTER
            ports.any { it in setOf(8008, 8009, 7000, 5555) } || has("mediarenderer", "chromecast", "roku", "television", "smart tv", " tv") -> DeviceKind.TV
            548 in ports || has("synology", "diskstation", "qnap", " nas") -> DeviceKind.NAS
            62078 in ports -> DeviceKind.PHONE
            ports.any { it in setOf(22, 3389, 445, 139) } -> DeviceKind.COMPUTER
            has("sonos", "hue", "echo", "nest", "smart plug", "bulb") -> DeviceKind.IOT
            else -> DeviceKind.UNKNOWN
        }
    }

    fun label(kind: DeviceKind, ar: Boolean): String = when (kind) {
        DeviceKind.ROUTER -> if (ar) "الراوتر" else "Router"
        DeviceKind.CAMERA -> if (ar) "كاميرا" else "Camera"
        DeviceKind.TV -> if (ar) "تلفاز أو جهاز بث" else "TV or streaming box"
        DeviceKind.NAS -> if (ar) "جهاز تخزين" else "Storage"
        DeviceKind.COMPUTER -> if (ar) "حاسوب" else "Computer"
        DeviceKind.PHONE -> if (ar) "هاتف أو جهاز لوحي" else "Phone or tablet"
        DeviceKind.PRINTER -> if (ar) "طابعة" else "Printer"
        DeviceKind.IOT -> if (ar) "جهاز منزلي ذكي" else "Smart home device"
        DeviceKind.UNKNOWN -> if (ar) "جهاز" else "Device"
    }
}
