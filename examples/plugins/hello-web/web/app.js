const source = document.querySelector("#source");
const clear = document.querySelector("#clear");
const fields = {
  characters: document.querySelector("#characters"),
  compact: document.querySelector("#compact"),
  words: document.querySelector("#words"),
  lines: document.querySelector("#lines"),
};

function update() {
  const value = source.value;
  fields.characters.textContent = String(Array.from(value).length);
  fields.compact.textContent = String(Array.from(value.replace(/\s/gu, "")).length);
  fields.words.textContent = String(value.trim() ? value.trim().split(/\s+/u).length : 0);
  fields.lines.textContent = String(value ? value.split(/\r\n?|\n/u).length : 0);
  clear.disabled = value.length === 0;
}

source.addEventListener("input", update);
clear.addEventListener("click", () => {
  source.value = "";
  source.focus();
  update();
});
update();
