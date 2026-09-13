# 职业皮肤

原始文件 `common.png`、`guard.png`、`engineer.png` 保留不变，分别对应无职业、守卫和工程师。

这三张 1254×1254 图片是非标准展开预览。经确认，使用 `scripts/import_profession_skins.py` 按部位裁切、缩放并重新排列为标准 64×64 RGBA、四像素宽手臂的皮肤。原图未完整提供的隐藏面复用同套图案的衣料、侧面和背面；移除预览黑底，工程师护目镜保留在头部外层。

运行 `python scripts/import_profession_skins.py`（需要 Pillow）会生成：

- `src/main/resources/assets/squire/textures/entity/avatar/common.png`
- `src/main/resources/assets/squire/textures/entity/avatar/guard.png`
- `src/main/resources/assets/squire/textures/entity/avatar/engineer.png`
- `build/skin-import-preview.png`：正面、背面与 UV 平面检查图，不是游戏截图。

渲染使用每名侍从独立同步的职业字段；转职后更新，重新加载档案时恢复。不再沿用主人的皮肤。护甲、手持物品与背包继续通过原有渲染层显示。
