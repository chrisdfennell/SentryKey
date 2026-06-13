import SwiftUI

/// One account row: label, live TOTP code, and a 30s countdown ring.
/// TimelineView ticks every second so the code and ring stay current.
struct AccountRow: View {
    let account: TwoFactorAccount

    private let brandOrange = Color(red: 1.0, green: 0.647, blue: 0.0)

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            let now = context.date
            let code = TOTP.code(secret: account.secret, date: now)
            let remaining = TOTP.secondsRemaining(date: now)
            let progress = Double(remaining) / 30.0

            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(account.label)
                        .font(.subheadline.bold())
                        .foregroundStyle(.white)
                        .lineLimit(1)
                    Text(formatted(code))
                        .font(.system(.title2, design: .monospaced).weight(.heavy))
                        .foregroundStyle(.white)
                }
                Spacer()
                ZStack {
                    Circle()
                        .stroke(Color.gray.opacity(0.3), lineWidth: 3)
                    Circle()
                        .trim(from: 0, to: progress)
                        .stroke(remaining <= 5 ? .red : brandOrange,
                                style: StrokeStyle(lineWidth: 3, lineCap: .round))
                        .rotationEffect(.degrees(-90))
                    Text("\(remaining)")
                        .font(.caption2)
                        .foregroundStyle(.gray)
                }
                .frame(width: 32, height: 32)
            }
            .padding(.vertical, 4)
            .contentShape(Rectangle())
            .onTapGesture {
                UIPasteboard.general.string = code
            }
        }
    }

    private func formatted(_ code: String) -> String {
        guard code.count == 6 else { return code }
        let mid = code.index(code.startIndex, offsetBy: 3)
        return "\(code[..<mid]) \(code[mid...])"
    }
}
