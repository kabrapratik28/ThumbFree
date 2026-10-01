public enum EngineError: Error, Equatable {
    case inputTooLong(samples: Int)
    case modelMissing(String)
    case loadFailed(String)
}
