// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "RemoteSession",
    platforms: [.iOS(.v15)],
    products: [
        .library(name: "RemoteSession", targets: ["RemoteSession"])
    ],
    targets: [
        .target(name: "RemoteSession"),
        .testTarget(name: "RemoteSessionTests", dependencies: ["RemoteSession"])
    ]
)
