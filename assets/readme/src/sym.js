// Sizes each <i class="sym" data-s="name" data-pt="font size"> like SwiftUI sizes an SF Symbol
// next to text. The PNGs are drawn at 64 pt and 2x (assets.swift).
for (const el of document.querySelectorAll(".sym[data-s]")) {
  const url = `sym/${el.dataset.s}.png`, img = new Image();
  img.onload = () => {
    const k = +el.dataset.pt / 64 / 2;
    el.style.width = img.naturalWidth * k + "px";
    el.style.height = img.naturalHeight * k + "px";
  };
  img.src = url;
  el.style.setProperty("--m", `url(${url})`);
}
