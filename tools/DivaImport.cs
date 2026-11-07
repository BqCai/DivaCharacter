using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Numerics;
using System.Reflection;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using System.Text;
using System.Web.Script.Serialization;
using System.Windows.Forms;
using MikuMikuLibrary.Archives;
using MikuMikuLibrary.Archives.CriMw;
using MikuMikuLibrary.Databases;
using MikuMikuLibrary.Materials;
using MikuMikuLibrary.Objects;
using MikuMikuLibrary.Textures;
using MikuMikuLibrary.IBLs;
using Half = MikuMikuLibrary.Misc.Half;

internal static class DivaImport
{
    [STAThread]
    public static int Main(string[] args)
    {
        Console.OutputEncoding = new UTF8Encoding(false);
        try
        {
            if (args.Length == 3 && args[0] == "browse")
            {
                using (var dialog = new FolderBrowserDialog())
                {
                    dialog.Description = "Select the MEGA39's+ installation folder";
                    dialog.ShowNewFolderButton = false;
                    if (Directory.Exists(args[2])) dialog.SelectedPath = args[2];
                    if (dialog.ShowDialog() == DialogResult.OK)
                        File.WriteAllText(OutputPath(args[1], "selected.txt"), dialog.SelectedPath, new UTF8Encoding(false));
                }
                return 0;
            }
            if (args.Length != 3 && args.Length != 4)
                throw new ArgumentException("Usage: DivaImport catalog|module|physics|faces|pv GAME_ROOT OUTPUT [ID]");
            string library = Path.Combine(args[1], "MikuMikuModel", "MikuMikuLibrary.dll");
            if (!File.Exists(library)) throw new FileNotFoundException("MikuMikuLibrary.dll is missing from the game's MikuMikuModel folder.");
            AppDomain.CurrentDomain.AssemblyResolve += (sender, request) =>
                new AssemblyName(request.Name).Name == "MikuMikuLibrary" ? Assembly.LoadFrom(library) : null;
            Run(args);
            return 0;
        }
        catch (Exception error)
        {
            Console.Error.WriteLine(error.GetBaseException().Message);
            return 1;
        }
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    private static void Run(string[] args)
    {
        using (var game = new GameArchives(args[1]))
        {
            Directory.CreateDirectory(OutputPath(args[2], ""));
            if (args[0] == "catalog" && args.Length == 3)
            {
                WriteJson(args[2], "catalog.json", game.Modules());
                WriteJson(args[2], "pv-catalog.json", DivaPv.Catalog(game));
            }
            else if ((args[0] == "module" || args[0] == "physics") && args.Length == 4)
                new ModuleExport(game, args[2], args[0] == "physics").Write(game.Modules().Single(m => m.id == args[3]));
            else if (args[0] == "pv" && args.Length == 4)
                DivaPv.Export(game, args[2], args[3]);
            else if (args[0] == "faces" && args.Length == 3)
            {
                new ModuleExport(game, args[2], false).WriteFaces();
                DivaPv.WriteGameplay(game, args[2]);
            }
            else throw new ArgumentException("Invalid import request.");
        }
    }

    internal static void WriteJson(string root, string name, object value)
    {
        var json = new JavaScriptSerializer { MaxJsonLength = int.MaxValue, RecursionLimit = 80 };
        File.WriteAllText(OutputPath(root, name), json.Serialize(value), new UTF8Encoding(false));
    }

    internal static string OutputPath(string root, string name)
    {
        string path = Path.Combine(Path.GetFullPath(root), name.Replace('/', '\\'));
        if (path.Length < 240 || path.StartsWith(@"\\?\", StringComparison.Ordinal)) return path;
        return path.StartsWith(@"\\", StringComparison.Ordinal) ? @"\\?\UNC\" + path.Substring(2) : @"\\?\" + path;
    }
}

internal sealed class ModuleRow
{
    public string id { get; set; }
    public string name { get; set; }
    public string character { get; set; }
    public string costume { get; set; }
}

internal sealed class GameArchives : IDisposable
{
    // Steam-specific entries take precedence over the platform packs used by Steam.
    private static readonly string[] Layers = {
        "rom_steam_region_dlc/rom/", "rom_steam_region/rom/", "rom_steam_dlc/rom/", "rom_steam/rom/",
        "rom_switch/rom/", "rom_ps4_dlc/rom/", "rom_ps4_patch/rom/", "rom_ps4/rom/", "rom/"
    };
    private readonly List<CpkArchive> archives = new List<CpkArchive>();
    private readonly Dictionary<string, CpkArchive> index = new Dictionary<string, CpkArchive>(StringComparer.OrdinalIgnoreCase);
    private const EntryStreamMode ReadMode = EntryStreamMode.MemoryStream;

    public GameArchives(string root)
    {
        try
        {
            foreach (var name in new[] { "diva_main.cpk", "diva_main_region.cpk", "diva_dlc00.cpk", "diva_dlc00_region.cpk" })
            {
                string path = Path.Combine(root, name);
                if (name.StartsWith("diva_dlc", StringComparison.Ordinal) && !File.Exists(path)) continue;
                var archive = new CpkArchive();
                archives.Add(archive);
                archive.Load(path);
                foreach (string entry in archive.FileNames) index[entry] = archive;
            }
        }
        catch { Dispose(); throw; }
    }

    public Stream Open(string entry)
    {
        CpkArchive archive;
        if (!index.TryGetValue(entry, out archive)) throw new FileNotFoundException("CPK entry: " + entry);
        return archive.Open(entry, ReadMode);
    }

    public bool Contains(string entry) { return index.ContainsKey(entry); }

    public string Find(string relative)
    {
        return Layers.Select(layer => layer + relative).FirstOrDefault(index.ContainsKey);
    }

    public Dictionary<string, string> Properties(string archivePath, string filename)
    {
        using (var stream = Open(archivePath))
        using (var farc = new FarcArchive())
        {
            farc.Load(stream, true);
            return ReadProperties(farc.Open(filename, ReadMode));
        }
    }

    internal static Dictionary<string, string> ReadProperties(Stream stream)
    {
        using (var reader = new StreamReader(stream))
        {
            var props = new Dictionary<string, string>(StringComparer.Ordinal);
            string line;
            while ((line = reader.ReadLine()) != null)
            {
                int separator = line.IndexOf('=');
                if (separator > 0 && line[0] != '#') props[line.Substring(0, separator)] = line.Substring(separator + 1);
            }
            return props;
        }
    }

    public ModuleRow[] Modules()
    {
        var modules = new Dictionary<int, ModuleRow>();
        foreach (var folder in new[] { "rom_steam_region/rom/", "rom_steam_region_dlc/rom/" })
        {
            string path = folder + "gm_module_tbl.farc";
            if (!index.ContainsKey(path)) continue;
            var fields = Properties(path, "gm_module_id.bin");
            foreach (var entry in fields.Where(e => e.Key.StartsWith("module.", StringComparison.Ordinal) && e.Key.EndsWith(".id", StringComparison.Ordinal)))
            {
                string key = entry.Key.Substring(0, entry.Key.Length - 2), costume;
                // The first adapter is calibrated for Miku's rig and supported material families.
                if (fields[key + "chara"] != "MIKU" || !fields.TryGetValue(key + "cos", out costume) || !costume.StartsWith("COS_")) continue;
                int id = int.Parse(entry.Value, CultureInfo.InvariantCulture);
                modules[id] = new ModuleRow { id = entry.Value, name = fields[key + "name"], character = "MIKU", costume = costume };
            }
        }
        if (modules.Count == 0) throw new InvalidDataException("No Miku modules in the Steam module tables.");
        return modules.OrderBy(pair => pair.Key).Select(pair => pair.Value).ToArray();
    }

    public void Dispose()
    {
        foreach (var archive in archives) archive.Dispose();
    }
}

internal sealed class ModuleExport
{
    private static readonly EntryStreamMode ReadMode = EntryStreamMode.MemoryStream;
    private readonly GameArchives game;
    private readonly string output;
    private readonly bool physicsOnly;
    private readonly ObjectDatabase objectDb = new ObjectDatabase();
    private readonly TextureDatabase textureDb = new TextureDatabase();
    private readonly BoneDatabase boneDb = new BoneDatabase();
    private readonly Dictionary<string, object> textureInfo = new Dictionary<string, object>();
    private readonly List<object> bones = new List<object>(), meshes = new List<object>(), materials = new List<object>(), parts = new List<object>();
    private readonly Dictionary<string, int> boneIds = new Dictionary<string, int>();
    private string resourcePrefix = "";

    public ModuleExport(GameArchives game, string output, bool physicsOnly)
    {
        this.game = game;
        this.output = output;
        this.physicsOnly = physicsOnly;
        using (var file = game.Open("rom_steam/rom/objset/obj_db.bin")) objectDb.Load(file, true);
        using (var file = game.Open("rom_steam/rom/objset/tex_db.bin")) textureDb.Load(file, true);
        using (var file = game.Open("rom/bone_data.bin")) boneDb.Load(file, true);
    }

    public void Write(ModuleRow module)
    {
        var items = game.Properties("rom_switch/rom/chritm_prop.farc", "mikitm_tbl.txt");
        int costumeId = int.Parse(module.costume.Substring(4), CultureInfo.InvariantCulture) - 1;
        string costume = items.Single(e => e.Key.StartsWith("cos.") && e.Key.EndsWith(".id") && e.Value == costumeId.ToString(CultureInfo.InvariantCulture)).Key;
        costume = costume.Substring(0, costume.Length - 2);
        ReadObject("MIKITM000", "MIKITM000_ATAM_HEAD_00__DIVSKN", "head");
        var itemKeys = items.Where(e => e.Key.StartsWith("item.") && e.Key.EndsWith(".no"))
            .ToDictionary(e => e.Value, e => e.Key.Substring(0, e.Key.Length - 2));
        for (int i = 0; i < Count(items, costume + "item.length"); i++)
        {
            string item = itemKeys[items[costume + "item." + i]];
            if (Count(items, item + "data.tex.length") > 0 || Count(items, item + "data.adjust.length") > 0)
                throw new NotSupportedException("Outfit item " + items[item + "no"] + " needs texture or attachment overrides.");
            var sets = Enumerable.Range(0, Count(items, item + "objset.length"))
                .Select(k => FindSet(items[item + "objset." + k])).ToArray();
            for (int j = 0; j < Count(items, item + "data.obj.length"); j++)
            {
                string name = items[item + "data.obj." + j + ".uid"];
                var set = sets.Single(s => s.Objects.Any(o => Same(o.Name, name)));
                string role = name.IndexOf("_ATAM_", StringComparison.OrdinalIgnoreCase) >= 0 ? "hair"
                    : name.IndexOf("_UDE_", StringComparison.OrdinalIgnoreCase) >= 0 ? "hands" : "body";
                ReadObject(set.Name, name, role);
            }
        }
        if (physicsOnly) DivaImport.WriteJson(output, "physics.json", parts);
        else DivaImport.WriteJson(output, "module.json", new { module, bones, meshes, materials, textures = textureInfo, parts });
    }

    public void WriteFaces()
    {
        resourcePrefix = "faces/";
        ReadObjects("MIKITM000", "head", Enumerable.Range(1, 8)
            .Select(id => "MIKITM000_ATAM_HEAD_" + id.ToString("D2", CultureInfo.InvariantCulture) + "__DIVSKN").ToArray(), 1);
        var lighting = WriteLighting();
        DivaImport.WriteJson(output, "faces.json", new { bones, meshes, materials, textures = textureInfo, lighting });
    }

    private object WriteLighting()
    {
        // This is the IBL used by the visual reference. The player's game supplies its data.
        string path = game.Find("ibl/ns123.ibl");
        if (path == null) throw new FileNotFoundException("Character lighting IBL: ns123.ibl");
        var ibl = new IBL();
        using (var stream = game.Open(path)) ibl.Load(stream, true);
        if (ibl.LightMaps.Count != 6 || ibl.Lights.Count == 0)
            throw new InvalidDataException("Unsupported character lighting IBL layout.");
        var bindings = new Dictionary<string, string>();
        bindings["9"] = WriteLightCube("character_diffuse", new[] { ibl.LightMaps[0], ibl.LightMaps[1] }, 2);
        for (int i = 2; i < 6; i++)
            bindings[(i + 8).ToString(CultureInfo.InvariantCulture)] = WriteLightCube("character_specular_" + i, new[] { ibl.LightMaps[i] }, 3);
        var direction = ibl.Lights[0].Direction;
        return new { source = path, direction = new[] { direction.X, direction.Y, direction.Z }, textures = bindings };
    }

    private string WriteLightCube(string key, LightMap[] maps, int levels)
    {
        var files = new List<string>();
        for (int face = 0; face < 6; face++)
        {
            float[] previous = null;
            int width = maps[0].Width;
            for (int mip = 0; mip < levels; mip++)
            {
                Half[] values;
                if (mip < maps.Length)
                {
                    values = maps[mip].Sides[face];
                    previous = Array.ConvertAll(values, value => (float)value);
                }
                else
                {
                    previous = FilterLightMip(previous, width);
                    values = Array.ConvertAll(previous, value => new Half(value));
                }
                byte[] bytes = new byte[values.Length * 2];
                var pin = GCHandle.Alloc(values, GCHandleType.Pinned);
                try { Marshal.Copy(pin.AddrOfPinnedObject(), bytes, 0, bytes.Length); }
                finally { pin.Free(); }
                string file = "faces/lighting/" + key + "/" + files.Count + ".bin";
                File.WriteAllBytes(OutputFile(file), bytes);
                files.Add(file);
                width /= 2;
            }
        }
        textureInfo[key] = new { width = maps[0].Width, height = maps[0].Height, faces = 6, levels,
            format = "RGBA16F", data = files, name = key };
        return key;
    }

    // ReDIVA light_param_ibl_specular_generate_mipmap, MIT, see THIRD-PARTY-NOTICES.md.
    private static float[] FilterLightMip(float[] source, int width)
    {
        float[] kernel = { .025f, .05f, .05f, .025f, .05f, .125f, .125f, .05f,
            .05f, .125f, .125f, .05f, .025f, .05f, .05f, .025f };
        float[] result = new float[width * width * 4];
        int sourceWidth = width * 2;
        for (int y = 0; y < width; y++) for (int x = 0; x < width; x++)
        {
            int destination = (y * width + x) * 4;
            float total = 0;
            for (int ky = 0; ky < 4; ky++) for (int kx = 0; kx < 4; kx++)
            {
                int sx = x * 2 + kx - 1, sy = y * 2 + ky - 1;
                if (sx < 0 || sy < 0 || sx >= sourceWidth || sy >= sourceWidth) continue;
                float weight = kernel[ky * 4 + kx];
                int sample = (sy * sourceWidth + sx) * 4;
                for (int c = 0; c < 3; c++) result[destination + c] += source[sample + c] * weight;
                total += weight;
            }
            for (int c = 0; c < 3; c++) result[destination + c] /= total;
            result[destination + 3] = 1;
        }
        return result;
    }

    private static int Count(Dictionary<string, string> fields, string key)
    {
        string value;
        return fields.TryGetValue(key, out value) ? int.Parse(value, CultureInfo.InvariantCulture) : 0;
    }

    private static bool Same(string left, string right) { return StringComparer.OrdinalIgnoreCase.Equals(left, right); }
    private ObjectSetInfo FindSet(string name) { return objectDb.ObjectSets.Single(s => Same(s.Name, name)); }

    private void ReadObject(string setName, string name, string role)
    {
        ReadObjects(setName, role, new[] { name }, 0);
    }

    private void ReadObjects(string setName, string role, string[] names, int firstVariant)
    {
        var db = FindSet(setName);
        string entry = game.Find("objset/" + db.ArchiveFileName);
        if (entry == null) throw new FileNotFoundException("Object set: " + db.ArchiveFileName);
        using (var input = game.Open(entry))
        using (var archive = new FarcArchive())
        {
            archive.Load(input, true);
            var textures = new TextureSet();
            var objects = new ObjectSet();
            using (var file = archive.Open(db.TextureFileName, ReadMode)) textures.Load(file, true);
            using (var file = archive.Open(db.FileName, ReadMode)) objects.Load(file, textures, textureDb, true);
            objects.TryFixParentBoneInfos(boneDb);
            for (int i = 0; i < names.Length; i++)
            {
                var obj = objects.Objects.Single(o => Same(o.Name, names[i]));
                if (firstVariant == 0) ReadPhysics(obj, role, entry);
                if (!physicsOnly) WriteObject(obj, textures, setName, role, firstVariant + i);
            }
        }
    }

    private void WriteObject(MikuMikuLibrary.Objects.Object obj, TextureSet textures, string setName, string role, int headVariant)
    {
        var palette = new List<int>();
        foreach (var bone in obj.Skin.Bones)
        {
            int id;
            if (!boneIds.TryGetValue(bone.Name, out id))
            {
                id = bones.Count;
                boneIds.Add(bone.Name, id);
                Matrix4x4 b = bone.InverseBindPoseMatrix;
                bones.Add(new { name = bone.Name, parent = bone.Parent == null ? "" : bone.Parent.Name,
                    inverse_bind = new[] { new[]{b.M11,b.M12,b.M13,b.M14}, new[]{b.M21,b.M22,b.M23,b.M24},
                        new[]{b.M31,b.M32,b.M33,b.M34}, new[]{b.M41,b.M42,b.M43,b.M44} } });
            }
            palette.Add(id);
        }
        int materialBase = materials.Count;
        foreach (var material in obj.Materials) WriteMaterial(material, role, setName, textures);
        foreach (var mesh in obj.Meshes)
            foreach (var sub in mesh.SubMeshes) WriteMesh(mesh, sub, role, materialBase, palette, headVariant);
    }

    private void ReadPhysics(MikuMikuLibrary.Objects.Object obj, string role, string entry)
    {
        string physical = game.Find("skin_param/ext_skp_" + obj.Name.ToLowerInvariant() + ".txt"), physicsFile = null;
        if (physical != null)
        {
            physicsFile = "import/physics/" + obj.Name + ".txt";
            using (var file = game.Open(physical)) using (var target = File.Create(OutputFile(physicsFile))) file.CopyTo(target);
        }
        if (!physicsOnly || physicsFile != null)
            parts.Add(new { name = obj.Name, role, entry, physicsFile,
                skinBlocks = obj.Skin.Blocks.Where(block => !physicsOnly || block.GetType().Name == "OsageBlock" || block.GetType().Name == "ExpressionBlock")
                    .Select(block => new { type = block.GetType().Name, data = block }).ToArray() });
    }

    private void WriteMaterial(Material material, string role, string setName, TextureSet textures)
    {
        var slots = new List<object>();
        foreach (var slot in material.MaterialTextures)
        {
            if (slot.Type.ToString() == "None") continue;
            uint id = slot.TextureId;
            var texture = textures.Textures.FirstOrDefault(t => t.Id == id);
            string key = null;
            if (texture != null)
            {
                key = (resourcePrefix == "" ? "import_" : "face_") + setName + "_" + id;
                if (!textureInfo.ContainsKey(key)) WriteTexture(key, texture);
            }
            slots.Add(new { Type = slot.Type.ToString(), TextureId = id, slot.RepeatU, slot.RepeatV, resource = key });
        }
        materials.Add(new { part = role, material.Name, material.ShaderName, material.ShaderFlags, material.BlendFlags,
            Diffuse = Rgba(material.Diffuse), Ambient = Rgba(material.Ambient), Specular = Rgba(material.Specular), Emission = Rgba(material.Emission),
            material.Shininess, material.AlphaTexture, material.AlphaMaterial, material.PunchThrough, material.DoubleSided,
            SrcBlendFactor = material.SrcBlendFactor.ToString(), DstBlendFactor = material.DstBlendFactor.ToString(), MaterialTextures = slots });
    }

    private static float[] Rgba(MikuMikuLibrary.Misc.Color value) { return new[] { value.R, value.G, value.B, value.A }; }

    private void WriteTexture(string key, Texture texture)
    {
        var files = new List<string>();
        for (int face = 0; face < texture.ArraySize; face++)
            foreach (var mip in texture.EnumerateMipMaps(face))
            {
                string path = resourcePrefix + "import/textures/" + key + "/" + files.Count + ".bin";
                File.WriteAllBytes(OutputFile(path), mip.Data);
                files.Add(path);
            }
        textureInfo[key] = new { width = texture.Width, height = texture.Height, faces = texture.ArraySize,
            levels = texture.MipMapCount, format = texture.Format.ToString(), data = files, name = texture.Name };
    }

    private void WriteMesh(Mesh mesh, SubMesh sub, string role, int materialBase, List<int> palette, int headVariant)
    {
        var triangles = sub.GetTriangles();
        if (triangles.Count == 0) return;
        var remap = new Dictionary<uint, uint>();
        string vertexFile = resourcePrefix + "import/meshes/" + meshes.Count + ".vbo", indexFile = resourcePrefix + "import/meshes/" + meshes.Count + ".ibo";
        var meshPalette = sub.BoneIndices.Select(i => palette[i]).ToArray();
        using (var vbo = new BinaryWriter(File.Create(OutputFile(vertexFile))))
        using (var ibo = new BinaryWriter(File.Create(OutputFile(indexFile))))
            foreach (var triangle in triangles)
                foreach (uint original in new[] { triangle.A, triangle.B, triangle.C })
                {
                    uint id;
                    if (!remap.TryGetValue(original, out id))
                    {
                        id = (uint)remap.Count;
                        remap.Add(original, id);
                        int i = checked((int)original);
                        var p = mesh.Positions[i];
                        var n = mesh.Normals == null ? Vector3.UnitY : mesh.Normals[i];
                        var t = mesh.Tangents == null ? new Vector4(1, 0, 0, 1) : mesh.Tangents[i];
                        var u = mesh.TexCoords0 == null ? Vector2.Zero : mesh.TexCoords0[i];
                        var v = mesh.TexCoords1 == null ? Vector2.Zero : mesh.TexCoords1[i];
                        var c = mesh.Colors0 == null ? new[] { 1f, 1f, 1f, 1f } : Rgba(mesh.Colors0[i]);
                        var skin = mesh.BoneWeights[i];
                        foreach (float x in new[]{p.X,p.Y,p.Z,1,n.X,n.Y,n.Z,0,c[0],c[1],c[2],c[3],u.X,u.Y,0,0,v.X,v.Y,0,0,t.X,t.Y,t.Z,t.W}) vbo.Write(x);
                        float[] weights = { skin.Weight1, skin.Weight2, skin.Weight3, skin.Weight4 };
                        int[] indices = { skin.Index1, skin.Index2, skin.Index3, skin.Index4 };
                        foreach (float weight in weights) vbo.Write(weight);
                        for (int j = 0; j < 4; j++)
                        {
                            if (weights[j] > 0 && (indices[j] < 0 || indices[j] >= meshPalette.Length)) throw new InvalidDataException("Invalid bone palette in " + mesh.Name);
                            vbo.Write(weights[j] > 0 ? (float)(indices[j] * 3) : -1f);
                        }
                    }
                    ibo.Write(id);
                }
        meshes.Add(new { name = mesh.Name, part = role, headVariant, material = materialBase + (int)sub.MaterialIndex,
            vertexFile, indexFile, vertices = remap.Count, indices = triangles.Count * 3, palette = meshPalette });
    }

    private string OutputFile(string relative)
    {
        string path = DivaImport.OutputPath(output, relative);
        Directory.CreateDirectory(Path.GetDirectoryName(path));
        return path;
    }
}
