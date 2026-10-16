"""仅静态读取 class 常量池，核对插件对各 Paper API 的直接成员引用；不启动 JVM 或服务端。"""
import argparse
import json
import struct
from pathlib import Path
from zipfile import ZipFile


class ClassFile:
    def __init__(self, data):
        self.data = data
        self.pos = 0
        if self.u4() != 0xCAFEBABE:
            raise ValueError("非法 class 文件")
        self.u2()
        self.major = self.u2()
        count = self.u2()
        self.cp = [None] * count
        index = 1
        while index < count:
            tag = self.u1()
            if tag == 1:
                length = self.u2()
                self.cp[index] = (tag, self.take(length).decode("utf-8", "replace"))
            elif tag in (3, 4):
                self.take(4)
            elif tag in (5, 6):
                self.take(8)
                index += 1
            elif tag in (7, 8, 16, 19, 20):
                self.cp[index] = (tag, self.u2())
            elif tag in (9, 10, 11, 12, 17, 18):
                self.cp[index] = (tag, self.u2(), self.u2())
            elif tag == 15:
                self.take(3)
            else:
                raise ValueError("未知常量池 tag：" + str(tag))
            index += 1
        self.u2()
        self.name = self.class_name(self.u2())
        parent = self.u2()
        self.parents = [self.class_name(parent)] if parent else []
        self.parents.extend(self.class_name(self.u2()) for _ in range(self.u2()))
        self.fields = self.members()
        self.methods = self.members()

    def take(self, count):
        value = self.data[self.pos:self.pos + count]
        self.pos += count
        return value

    def u1(self): return self.take(1)[0]
    def u2(self): return struct.unpack(">H", self.take(2))[0]
    def u4(self): return struct.unpack(">I", self.take(4))[0]
    def text(self, index): return self.cp[index][1]
    def class_name(self, index): return self.text(self.cp[index][1])

    def members(self):
        result = set()
        for _ in range(self.u2()):
            self.u2()
            name, descriptor = self.text(self.u2()), self.text(self.u2())
            result.add((name, descriptor))
            for _ in range(self.u2()):
                self.u2()
                self.take(self.u4())
        return result

    def references(self):
        for item in self.cp:
            if not item or item[0] not in (9, 10, 11):
                continue
            kind, owner, member = item
            name_type = self.cp[member]
            yield kind, self.class_name(owner), self.text(name_type[1]), self.text(name_type[2])


def api_classes(path):
    with ZipFile(path) as archive:
        return {name[:-6]: ClassFile(archive.read(name)) for name in archive.namelist()
                if name.endswith(".class") and not name.startswith("META-INF/")}


def exists(classes, owner, kind, name, descriptor, visited=None):
    if visited is None: visited = set()
    if owner in visited: return False
    visited.add(owner)
    info = classes.get(owner)
    if info is None:
        # 只认可明确已知的 JDK 继承成员；未知父类不能自动替缺失的 API 成员兜底。
        object_members = {("toString", "()Ljava/lang/String;"), ("hashCode", "()I"),
                          ("equals", "(Ljava/lang/Object;)Z"), ("getClass", "()Ljava/lang/Class;"),
                          ("clone", "()Ljava/lang/Object;")}
        enum_members = object_members | {("name", "()Ljava/lang/String;"), ("ordinal", "()I"),
                          ("getDeclaringClass", "()Ljava/lang/Class;"),
                          ("compareTo", "(Ljava/lang/Enum;)I"), ("compareTo", "(Ljava/lang/Object;)I")}
        return kind != 9 and ((owner == "java/lang/Object" and (name, descriptor) in object_members)
                             or (owner == "java/lang/Enum" and (name, descriptor) in enum_members))
    members = info.fields if kind == 9 else info.methods
    if (name, descriptor) in members: return True
    if name == "<init>": return False
    return any(exists(classes, parent, kind, name, descriptor, visited) for parent in info.parents)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--classes", type=Path, required=True)
    parser.add_argument("--api", action="append", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.classes.is_file():
        with ZipFile(args.classes) as archive:
            plugin = [ClassFile(archive.read(name)) for name in archive.namelist()
                      if name.endswith(".class") and not name.startswith("META-INF/")]
    else:
        plugin = [ClassFile(path.read_bytes()) for path in args.classes.rglob("*.class")]
    refs = []
    for info in plugin:
        if not info.name.startswith("com/wenkrang/famara/"): continue
        for kind, owner, name, descriptor in info.references():
            if owner.startswith(("org/bukkit/", "io/papermc/paper/")):
                refs.append((info.name, kind, owner, name, descriptor))
    results = []
    for path in args.api:
        classes = api_classes(path)
        missing = []
        for source, kind, owner, name, descriptor in refs:
            if not exists(classes, owner, kind, name, descriptor):
                missing.append({"source": source, "owner": owner, "name": name, "descriptor": descriptor})
        results.append({"api": path.name, "direct_references": len(refs), "missing": missing})
    report = {"kind": "static-member-linkage-only", "runtime_tests_executed": False,
              "plugin_class_majors": sorted({info.major for info in plugin}), "apis": results}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=True, indent=2))
    if any(result["missing"] for result in results): raise SystemExit(1)


if __name__ == "__main__":
    main()
