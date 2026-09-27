import SwiftUI
import PodorShared

@main
struct PodorApp: App {
    var body: some Scene {
        WindowGroup { StudioView().ignoresSafeArea() }
    }
}

struct StudioView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

