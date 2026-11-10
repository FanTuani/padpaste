import AppKit
import Foundation
let args = CommandLine.arguments
let board = NSPasteboard.general
switch args[1] {
case "snapshot":
    let items = (board.pasteboardItems ?? []).map { item in
        Dictionary(uniqueKeysWithValues: item.types.compactMap { type in
            item.data(forType: type).map { (type.rawValue, $0.base64EncodedString()) }
        })
    }
    try JSONSerialization.data(withJSONObject: items).write(to: URL(fileURLWithPath: args[2]))
case "restore":
    let saved = try JSONSerialization.jsonObject(with: Data(contentsOf: URL(fileURLWithPath: args[2]))) as! [[String: String]]
    let items = saved.map { values -> NSPasteboardItem in
        let item = NSPasteboardItem()
        for (type, data) in values { item.setData(Data(base64Encoded: data)!, forType: .init(type)) }
        return item
    }
    board.clearContents(); board.writeObjects(items)
case "inspect":
    let image = NSImage(pasteboard: board)
    let info: [String: Any] = ["width": image?.size.width ?? 0, "height": image?.size.height ?? 0,
        "fileURL": board.string(forType: .fileURL) ?? "", "png": board.data(forType: .png)?.base64EncodedString() ?? "", "changeCount": board.changeCount]
    print(String(data: try JSONSerialization.data(withJSONObject: info), encoding: .utf8)!)
default: exit(1)
}
