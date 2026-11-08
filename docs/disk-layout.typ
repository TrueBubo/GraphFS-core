#import "@preview/cetz:0.4.2"

#set page(width: auto, height: auto, margin: 1cm)
#set text(font: "New Computer Modern", size: 8pt)

// The indexed word whose index lives on the nodes chosen by the hash ring.
#let word = "location"

#let c-super = rgb("#9ecae1")
#let c-wal = rgb("#c6dbef")
#let c-remap = rgb("#6baed6")
#let c-header = rgb("#fdae6b")
#let c-meta = rgb("#a1d99b")
#let c-content = rgb("#fee6ce")
#let c-index = rgb("#dadaeb")
#let c-free = rgb("#f2f2f2")
#let c-hi = rgb("#e6550d")

#let disk-h = 1.2
#let disk-w = 27
#let pitch = 5.2

// Horizontal layout shared by every disk, so segments line up vertically.
#let x-sb1 = 0
#let x-sb2 = 1.2
#let x-wal = 2.4
#let x-remap = 5.6
#let x-file = 8.4
#let ptr-w = 0.9
#let x-small = 14.6
#let x-meta = 17.4
#let meta-w = 1.4
#let x-index = 23.8

#cetz.canvas({
  import cetz.draw: *

  let seg(x, y, w, fill, body, h: disk-h) = {
    rect((x, y), (x + w, y + h), fill: fill, stroke: 0.6pt)
    content((x + w / 2, y + h / 2), align(center, body))
  }

  let arrow = (end: "stealth", fill: black, scale: 0.8)

  // Pointer cells of a file header, in on-disk order.
  let ptrs = ([raft\ state\ ptr], [tags\ ptr], [relat.\ ptr], [adj.\ freq.\ ptr])
  // Pointed-to blocks, reversed so the pointer arrows nest instead of crossing.
  let metas = ([adjacency\ frequency\ graph], [relation-\ ships], [tags], [raft\ state])

  let disk(i, has134, small, has-index) = {
    let y = -(i - 1) * pitch
    let top = y + disk-h

    content((-0.4, y + disk-h / 2), anchor: "east", text(size: 12pt, weight: "bold")[Node #i])
    rect((x-sb1, y), (disk-w, top), fill: c-free, stroke: 1pt)

    seg(x-sb1, y, 1.2, c-super)[super-\ block 1]
    seg(x-sb2, y, 1.2, c-super)[super-\ block 2]
    seg(x-wal, y, 3.2, c-wal)[WAL]
    seg(x-remap, y, 2.0, c-remap)[inode\ remaps]

    // Both superblocks point to the inode remap table.
    bezier((x-sb1 + 0.6, top), (x-remap + 0.7, top), (x-sb1 + 0.6, top + 1.6), (x-remap + 0.7, top + 1.6), mark: arrow)
    bezier((x-sb2 + 0.6, top), (x-remap + 1.3, top), (x-sb2 + 0.6, top + 0.9), (x-remap + 1.3, top + 0.9), mark: arrow)

    if has134 {
      for (k, p) in ptrs.enumerate() {
        seg(x-file + k * ptr-w, y, ptr-w, c-header, text(size: 6.5pt, p))
      }
      let x-content = x-file + 4 * ptr-w
      seg(x-content, y, 2.0, c-content)[content]
      content((x-file + (4 * ptr-w + 2.0) / 2, top + 0.3), text(weight: "bold")[file 134:1])

      for (k, m) in metas.enumerate() {
        seg(x-meta + k * meta-w, y, meta-w, c-meta, text(size: 7pt, m))
      }
      for k in range(4) {
        let from = x-file + k * ptr-w + ptr-w / 2
        let to = x-meta + (3 - k) * meta-w + meta-w / 2
        let depth = 0.6 + (3 - k) * 0.45
        bezier((from, y), (to, y), (from, y - depth * 1.33), (to, y - depth * 1.33), mark: arrow)
      }
    }

    if small != none {
      seg(x-small, y, 0.8, c-header, text(size: 6.5pt)[ptrs])
      seg(x-small + 0.8, y, 1.4, c-content)[content]
      content((x-small + 1.1, top + 0.3), text(weight: "bold")[file #small])
    }

    if has-index {
      let lh = 0.45
      seg(x-index, y + disk-h - lh, 2.2, c-index, h: lh)[index "#word"]
      seg(x-index, y, 1.1, c-index, h: disk-h - lh)[13:1]
      seg(x-index + 1.1, y, 1.1, c-index, h: disk-h - lh)[24:1]
      rect((x-index, y), (x-index + 2.2, top), stroke: 1.4pt + c-hi, name: "index" + str(i))
    }
  }

  disk(1, true, "13:1", true)
  disk(2, false, "24:1", false)
  disk(3, true, "13:1", true)
  disk(4, true, "24:1", false)

  // Consistent hash ring.
  let cx = 33
  let cy = -1.5 * pitch + disk-h / 2
  let r = 3.2
  let at(a, rr: r) = (cx + rr * calc.cos(a * 1deg), cy + rr * calc.sin(a * 1deg))

  content((cx, cy + r + 1.4), text(size: 12pt, weight: "bold")[Consistent hash ring])
  circle((cx, cy), radius: r, stroke: 1.2pt + gray)

  // Walk clockwise (decreasing angle) from the word's hash to its two successors.
  let walk(a, b, rr) = line(..range(31).map(t => at(a + (b - a) * t / 30, rr: rr)), stroke: 1.2pt + c-hi, mark: (end: "stealth", fill: c-hi, scale: 0.8))

  let a-hash = 65
  let ring = ((4, 140), (1, 20), (3, -75), (2, -160))
  walk(a-hash - 4, 20 + 9, r + 0.35)
  walk(20 - 9, -75 + 9, r + 0.35)

  circle(at(a-hash), radius: 0.12, fill: black)
  content(at(a-hash, rr: r + 0.9), [hash("#word")])

  for (n, a) in ring {
    let hi = n == 1 or n == 3
    circle(at(a), radius: 0.42, fill: if hi { c-hi } else { white }, stroke: 1pt, name: "ring" + str(n))
    content(at(a), text(size: 10pt, weight: "bold", fill: if hi { white } else { black }, str(n)))
  }
  content(at(20, rr: r - 1.3), text(fill: c-hi, weight: "bold")[replica])
  content(at(-75, rr: r - 1.1), text(fill: c-hi, weight: "bold")[replica])

  content((cx, cy - r - 1.5), align(center, box(width: 7.5cm)[
    The word "#word" hashes onto the ring; its index is stored on the first
    two nodes clockwise from that point (replication factor 2): nodes 1 and 3.
  ]))

  // Tie the ring's chosen nodes to the index segments on their disks.
  let link = (dash: "dashed", paint: c-hi, thickness: 0.8pt)
  bezier("index1.east", "ring1.east", (rel: (10, 0), to: "index1.east"), (rel: (2.5, 0), to: "ring1.east"), stroke: link, mark: (end: "stealth", fill: c-hi))
  bezier("index3.east", "ring3.west", (rel: (2.5, 0), to: "index3.east"), (rel: (-2, 0), to: "ring3.west"), stroke: link, mark: (end: "stealth", fill: c-hi))

  // Legend.
  let ly = -3 * pitch - 3.2
  let items = (
    (c-super, [superblock]),
    (c-wal, [write-ahead log]),
    (c-remap, [inode remaps]),
    (c-header, [file header (pointers)]),
    (c-meta, [file metadata blocks]),
    (c-content, [file content]),
    (c-index, [word index]),
    (c-free, [free space]),
  )
  for (k, (c, label)) in items.enumerate() {
    let lx = k * 3.4
    rect((lx, ly), (lx + 0.5, ly + 0.4), fill: c, stroke: 0.6pt)
    content((lx + 0.65, ly + 0.2), anchor: "west", label)
  }
})
