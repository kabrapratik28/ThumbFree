import CoreML

public enum ComputeDevices {
    public static var hasNeuralEngine: Bool {
        MLComputeDevice.allComputeDevices.contains {
            if case .neuralEngine = $0 { return true } else { return false }
        }
    }
}
