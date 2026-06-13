import SwiftUI
import VisionKit

/// Wraps VisionKit's DataScannerViewController to scan a QR code and return its
/// string payload. Requires a real device with a camera (iOS 16+).
struct QRScannerView: UIViewControllerRepresentable {
    var onScanned: (String) -> Void
    @Environment(\.dismiss) private var dismiss

    func makeUIViewController(context: Context) -> DataScannerViewController {
        let scanner = DataScannerViewController(
            recognizedDataTypes: [.barcode(symbologies: [.qr])],
            qualityLevel: .balanced,
            isHighFrameRateTrackingEnabled: false,
            isHighlightingEnabled: true
        )
        scanner.delegate = context.coordinator
        try? scanner.startScanning()
        return scanner
    }

    func updateUIViewController(_ controller: DataScannerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        private let parent: QRScannerView
        init(_ parent: QRScannerView) { self.parent = parent }

        func dataScanner(_ dataScanner: DataScannerViewController, didAdd addedItems: [RecognizedItem], allItems: [RecognizedItem]) {
            for item in addedItems {
                if case let .barcode(barcode) = item, let value = barcode.payloadStringValue {
                    dataScanner.stopScanning()
                    parent.onScanned(value)
                    parent.dismiss()
                    break
                }
            }
        }
    }
}
