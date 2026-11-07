using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text;
using MikuMikuLibrary.Archives;
using MikuMikuLibrary.Databases;
using MikuMikuLibrary.Motions;
using MikuMikuLibrary.Skeletons;

internal static class DivaPv
{
    internal sealed class Row
    {
        public string id { get; set; }
        public string name { get; set; }
        public bool available { get; set; }
        internal string song, script, camera, motions;
        internal Dictionary<int, string> motionNames;
    }

    // Future Tone DSC argument counts, indexed by opcode. See ReDIVA KKdLib/dsc.cpp.
    private static readonly int[] Arguments = {
        0,1,4,2,2,2,7,4,2,6,2,1,6,2,1,1,3,2,3,5,5,4,4,5,2,0,2,4,2,2,1,21,
        0,3,2,5,1,1,7,1,1,2,1,2,1,2,3,3,1,2,2,3,6,6,1,1,2,3,1,2,2,4,4,1,
        2,1,2,1,1,3,3,3,2,1,9,3,2,4,2,3,2,24,1,2,1,3,1,3,4,1,2,6,3,2,3,3,
        4,1,1,3,3,4,1,3,3,8,2
    };
    private sealed class Event
    {
        public int time, start, index;
        public float speed;
        public int blend;
    }
    private sealed class Timeline
    {
        public int music = -1, end = -1;
        public readonly List<Event> moves = new List<Event>(), shots = new List<Event>();
        public readonly List<Partial> partials = new List<Partial>();
    }

    private sealed class Partial
    {
        public int time, layer, index, milliseconds, amount;
    }

    // Future Tone DSC indices into the character's rob_cmn_mottbl, not motion IDs.
    private static readonly int[][] PartialTable = {
        new[] {11,15,57,19,23,25,29,33,37,41,45,49,53,65,7,69,73,77,81,85,89,7,61,6,
            214,215,216,217,218,219,220,221,222,223,93,95,97,99,101,103,105,107,109,111,113,115,
            117,119,121,123,125,127,13,21,31,39,43,47,51,55,67,71,79,83,129,59,17,91,27,87,35,75,9,63,236,238,240,242},
        new[] {134,140,142,146,144,148,150,152,132,131,136,138,154,155,156,157,158,159,160,161,162,163,164,
            151,135,143,147,145,133,137,139,141,149,153,244,245,246,247,248,249,250,251,252},
        new[] {168,170,174,172,178,176,182,180,166,165,224,169,171,175,173,179,177,183,181,167},
        null,
        new[] {195,196,194,197,201,198,199,202,203,192,200,204,204,204,193}
    };

    public static Row[] Catalog(GameArchives game)
    {
        var fields = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (string path in new[] { "rom_steam_region/rom/pv_db.txt", "rom_steam_region_dlc/rom/mdata_pv_db.txt" })
            if (game.Contains(path))
                foreach (var field in GameArchives.ReadProperties(game.Open(path))) fields[field.Key] = field.Value;
        var rows = new List<Row>();
        foreach (var field in fields.Where(e => e.Key.StartsWith("pv_") && e.Key.EndsWith(".song_name")).OrderBy(e => e.Key))
        {
            string key = field.Key.Substring(0, field.Key.Length - "song_name".Length);
            int number;
            if (!int.TryParse(key.Substring(3, key.Length - 4), out number) || number <= 0) continue;
            string id = number.ToString("D3", CultureInfo.InvariantCulture), name;
            if (!fields.TryGetValue(key + "song_name_en", out name) || String.IsNullOrWhiteSpace(name)) name = field.Value;
            var row = new Row { id = id, name = name, song = Resolve(game, Get(fields, key + "song_file_name")),
                camera = game.Find("auth_3d/CAMPV" + id + ".farc"), motions = game.Find("rob/mot_PV" + id + ".farc"),
                motionNames = new Dictionary<int, string>() };
            foreach (string difficulty in new[] { "normal", "easy", "hard", "extreme", "encore" })
            {
                row.script = Resolve(game, Get(fields, key + "difficulty." + difficulty + ".0.script_file_name"));
                if (row.script != null) break;
            }
            string motionKey = key + "motion.";
            foreach (var motion in fields.Where(e => e.Key.StartsWith(motionKey, StringComparison.Ordinal)))
            {
                int index;
                if (int.TryParse(motion.Key.Substring(motionKey.Length), out index)) row.motionNames[index] = motion.Value;
            }
            row.available = Get(fields, key + "performer.num") == "1" && Get(fields, key + "performer.0.chara") == "MIK"
                && row.song != null && row.script != null && row.camera != null && row.motions != null && row.motionNames.Count != 0;
            rows.Add(row);
        }
        return rows.ToArray();
    }

    private static string Get(Dictionary<string, string> values, string key)
    {
        string value;
        return values.TryGetValue(key, out value) ? value : "";
    }

    private static string Resolve(GameArchives game, string path)
    {
        return path.StartsWith("rom/", StringComparison.Ordinal) ? game.Find(path.Substring(4))
            : game.Contains(path) ? path : null;
    }

    private static Timeline ReadTimeline(Stream stream)
    {
        var timeline = new Timeline();
        var motionStarts = new Dictionary<int, int>();
        var cameraSlots = new Dictionary<int, Event>();
        using (var reader = new BinaryReader(stream))
        {
            uint signature = reader.ReadUInt32();
            if (signature != 0x13122519 && signature != 0x14012316 && signature != 0x14031318 &&
                signature != 0x14050921 && signature != 0x15021718 && signature != 0x15122517 && signature != 0x16030121)
                throw new NotSupportedException("Unsupported PV script signature: " + signature.ToString("X8"));
            int time = 0, branch = 0;
            while (stream.Position < stream.Length)
            {
                int opcode = reader.ReadInt32();
                if (opcode < 0 || opcode >= Arguments.Length) throw new InvalidDataException("Unknown PV command " + opcode);
                if (opcode == 0) break;
                int[] data = new int[Arguments[opcode]];
                for (int i = 0; i < data.Length; i++) data[i] = reader.ReadInt32();
                if (opcode == 1) { time = data[0]; continue; }
                if (opcode == 65) { branch = data[0]; continue; }
                if (opcode == 32) { timeline.end = time; break; }
                if (branch == 2) continue; // Use the ordinary route when no rhythm-game inputs are present.
                if (opcode >= 18 && opcode <= 22 && data[0] == 0)
                {
                    var cue = new Partial { time = time, index = data[1], milliseconds = data[2], amount = -1 };
                    switch (opcode)
                    {
                        case 18: cue.layer = 3; break; // Eyelid state relative to the current expression.
                        case 19: cue.layer = 1; cue.index = data[2]; cue.milliseconds = data[3]; cue.amount = data[4]; break;
                        case 20:
                            if (data[1] < 0 || data[1] > 1) continue;
                            cue.layer = 4 + data[1]; cue.index = data[2]; cue.milliseconds = data[3]; cue.amount = data[4];
                            break;
                        case 21: cue.layer = 2; cue.amount = data[3]; break;
                        case 22: cue.layer = 0; cue.amount = data[3]; break;
                    }
                    timeline.partials.Add(cue);
                }
                if (opcode == 25 && timeline.music < 0) timeline.music = time;
                if (opcode == 8 && data[0] == 0)
                {
                    if (data[1] >= 0) motionStarts[data[1]] = time;
                    else motionStarts.Clear();
                }
                if (opcode == 7 && data[0] == 0 && data[1] > 0)
                {
                    int start;
                    if (!motionStarts.TryGetValue(data[1], out start)) start = time;
                    timeline.moves.Add(new Event { time = time, start = start, index = data[1],
                        speed = data[3] / 1000f, blend = data[2] });
                }
                if (opcode == 66 || opcode == 13)
                {
                    Event shot;
                    if (!cameraSlots.TryGetValue(data[0], out shot) || shot.index != data[1] || opcode == 66)
                        cameraSlots[data[0]] = shot = new Event { start = time, index = data[1] };
                    if (opcode == 13 && data[1] > 0)
                        timeline.shots.Add(new Event { time = time, start = shot.start, index = shot.index });
                }
            }
        }
        if (timeline.music < 0 || timeline.end <= timeline.music || timeline.moves.Count == 0 || timeline.shots.Count == 0)
            throw new InvalidDataException("The PV script does not contain a complete motion, camera and music timeline.");
        return timeline;
    }

    public static void Export(GameArchives game, string output, string id)
    {
        Row row = Catalog(game).SingleOrDefault(p => p.id == id && p.available);
        if (row == null) throw new NotSupportedException("This PV does not have the required solo Miku 3D resources.");
        Timeline timeline = ReadTimeline(game.Open(row.script));
        var skeletons = new BoneDatabase();
        using (var stream = game.Open("rom/bone_data.bin")) skeletons.Load(stream, true);
        var skeleton = skeletons.Skeletons.Single(s => s.Name == "MIK");
        var motions = new MotionSet();
        using (var archive = OpenFarc(game, row.motions))
        using (var stream = archive.Open("mot_PV" + id + ".bin", EntryStreamMode.MemoryStream)) motions.Load(stream, true);
        MotionDatabase database = null, commonDatabase = null;
        var positions = new Dictionary<string, int>(StringComparer.OrdinalIgnoreCase);
        foreach (string path in new[] { game.Find("rob/mot_db.farc"), "rom_ps4/rom/rob/mot_db.farc" }.Distinct())
        {
            if (path == null || !game.Contains(path)) continue;
            var candidate = new MotionDatabase();
            using (var archive = OpenFarc(game, path))
            using (var stream = archive.Open("mot_db.bin", EntryStreamMode.MemoryStream)) candidate.Load(stream, true);
            if (commonDatabase == null) commonDatabase = candidate;
            if (database == null) database = candidate;
            var set = candidate.MotionSets.SingleOrDefault(m => m.Name.Equals("PV" + id, StringComparison.OrdinalIgnoreCase));
            if (set == null || motions.Motions.Count != set.Motions.Count) continue;
            database = candidate;
            for (int i = 0; i < set.Motions.Count; i++) positions[set.Motions[i].Name] = i;
            break;
        }
        // Some PS4 DLC sets are absent from both databases. A one-clip archive with one named
        // slot is unambiguous; the database still supplies the bone-ID vocabulary.
        if (positions.Count == 0 && motions.Motions.Count == 1 && row.motionNames.Count == 1)
            positions[row.motionNames.Values.Single()] = 0;
        if (positions.Count == 0 || database == null)
            throw new NotSupportedException("No matching motion database entry for PV" + id);
        using (var writer = new BinaryWriter(File.Create(DivaImport.OutputPath(output, "preview.bin"))))
        {
            writer.Write(0x33505644); // DVP3: adds native head changes alongside the DSC partials.
            writer.Write(timeline.music); writer.Write(timeline.end);
            var moveIndices = timeline.moves.Select(e => e.index).Distinct().ToArray();
            writer.Write(moveIndices.Length);
            foreach (int index in moveIndices)
            {
                string motion;
                if (!row.motionNames.TryGetValue(index, out motion)) throw new NotSupportedException("Missing PV motion slot " + index);
                int position;
                if (!positions.TryGetValue(motion, out position)) throw new NotSupportedException("Motion not in this PV archive: " + motion);
                writer.Write(index);
                WriteMotion(writer, motions.Motions[position], skeleton, database, false);
            }
            WriteEvents(writer, timeline.moves, true);
            using (var archive = OpenFarc(game, row.camera))
            {
                int[] shots = timeline.shots.Select(e => e.index).Distinct().ToArray();
                writer.Write(shots.Length);
                foreach (int shot in shots)
                {
                    string name = "CAMPV" + id + (shot == 1 ? "_BASE" : "_PARTS" + shot.ToString("D2", CultureInfo.InvariantCulture)) + ".a3da";
                    string actual = archive.FileNames.SingleOrDefault(f => f.Equals(name, StringComparison.OrdinalIgnoreCase));
                    if (actual == null) throw new NotSupportedException("Missing camera shot " + name);
                    using (var stream = archive.Open(actual, EntryStreamMode.MemoryStream))
                    {
                        try { WriteShot(writer, shot, stream); }
                        catch (FormatException error) { throw new NotSupportedException("Unsupported A3DA properties in " + name + ": " + error.Message); }
                    }
                }
            }
            WriteEvents(writer, timeline.shots, false);
            WritePartials(writer, game, timeline.partials, skeleton, commonDatabase);
        }
        using (var song = game.Open(row.song))
        {
            var header = new byte[4];
            if (song.Read(header, 0, 4) != 4 || Encoding.ASCII.GetString(header) != "OggS")
                throw new NotSupportedException("This PV audio is not an Ogg stream.");
            song.Position = 0;
            using (var file = File.Create(DivaImport.OutputPath(output, "song.ogg"))) song.CopyTo(file);
        }
    }

    private static void WriteMotion(BinaryWriter writer, Motion motion, Skeleton skeleton,
                                    MotionDatabase database, bool partial)
    {
        writer.Write((int)motion.FrameCount);
        var tracks = motion.Bind(skeleton, database).BoneBindings
            .Where(t => !partial || IsPartialTrack(t.Name)).ToArray();
        writer.Write(tracks.Length);
        foreach (var track in tracks)
        {
            WriteString(writer, track.Name);
            WriteVector(writer, track.Position);
            WriteVector(writer, track.Rotation);
        }
    }

    private static bool IsPartialTrack(string name)
    {
        return name.StartsWith("tl_kuti_") || name.StartsWith("tl_tooth_") || name == "kl_ago_wj" ||
            name.StartsWith("tl_mayu_") || name.StartsWith("tl_mabu_") || name.StartsWith("tl_eyelid_") ||
            name.StartsWith("tl_hoho_") || name.StartsWith("kl_eye_") || name.StartsWith("n_eye_") ||
            name.StartsWith("nl_hito_") || name.StartsWith("nl_naka_") || name.StartsWith("nl_oya_") ||
            name.StartsWith("nl_kusu_") || name.StartsWith("nl_ko_");
    }

    private static void WritePartials(BinaryWriter writer, GameArchives game, List<Partial> commands,
                                      Skeleton skeleton, MotionDatabase database)
    {
        if (commands.Count == 0) { writer.Write(0); writer.Write(0); writer.Write(0); return; }
        var motions = ReadCommonMotions(game, database, null);
        int[] table = ReadCommonTable(game);
        int faceType = 7, lid = 2, head = 0;
        var cues = new List<Partial>();
        var heads = new List<Event>();
        foreach (var cue in commands.OrderBy(cue => cue.time))
        {
            int type = -1;
            if (cue.layer == 3)
            {
                if (cue.index != 0) lid = cue.index;
                if (lid == 1 || lid == 3)
                    type = faceType == 23 || faceType == 59 || faceType == 57 || faceType >= 214 && faceType <= 223
                        ? 8 : faceType + 1;
            }
            else
            {
                int[] indices = PartialTable[Math.Min(4, cue.layer)];
                if (cue.index >= 0 && cue.index < indices.Length) type = indices[cue.index];
                if (cue.layer == 0)
                {
                    faceType = type < 0 ? 7 : type;
                    int next = HeadVariant(faceType);
                    if (next != head) heads.Add(new Event { time = cue.time, index = next });
                    head = next;
                    lid = 2;
                    cues.Add(new Partial { time = cue.time, layer = 3, index = -1, milliseconds = cue.milliseconds, amount = -1 });
                }
            }
            cue.index = type >= 0 && type < table.Length && motions.ContainsKey(table[type]) ? table[type] : -1;
            cues.Add(cue);
        }
        int[] used = cues.Where(cue => cue.index >= 0).Select(cue => cue.index).Distinct().ToArray();
        writer.Write(used.Length);
        foreach (int id in used)
        {
            writer.Write(id);
            WriteMotion(writer, motions[id], skeleton, database, true);
        }
        writer.Write(cues.Count);
        foreach (var cue in cues)
        {
            writer.Write(cue.time);
            writer.Write(cue.layer);
            writer.Write(cue.index);
            writer.Write(cue.milliseconds < 0 ? (cue.layer >= 4 ? 0 : 100) : cue.milliseconds);
            writer.Write(cue.amount < 0 || cue.amount > 1000 ? 1f : cue.amount / 1000f);
        }
        writer.Write(heads.Count);
        foreach (var change in heads) { writer.Write(change.time); writer.Write(change.index); }
    }

    private static int HeadVariant(int faceType)
    {
        switch (faceType)
        {
            case 57: return 1;
            case 59: return 7;
            case 117: return 2;
            case 119: return 3;
            case 121: return 4;
            case 123: return 5;
            case 125: return 6;
            default: return 0;
        }
    }

    private static Dictionary<int, Motion> ReadCommonMotions(GameArchives game, MotionDatabase database, Dictionary<string, int> ids)
    {
        var motions = new Dictionary<int, Motion>();
        foreach (string name in new[] { "CMN", "MIK" })
        {
            var set = database.MotionSets.Single(s => s.Name == name);
            var source = new MotionSet();
            using (var archive = OpenFarc(game, game.Find("rob/mot_" + name + ".farc")))
            using (var stream = archive.Open("mot_" + name + ".bin", EntryStreamMode.MemoryStream))
                source.Load(stream, true);
            if (set.Motions.Count != source.Motions.Count) throw new InvalidDataException("Mismatched common motion database.");
            for (int i = 0; i < set.Motions.Count; i++)
            {
                int id = (int)set.Motions[i].Id;
                motions[id] = source.Motions[i];
                if (ids != null) ids[set.Motions[i].Name] = id;
            }
        }
        return motions;
    }

    public static void WriteGameplay(GameArchives game, string output)
    {
        var skeletons = new BoneDatabase();
        using (var stream = game.Open("rom/bone_data.bin")) skeletons.Load(stream, true);
        var skeleton = skeletons.Skeletons.Single(s => s.Name == "MIK");
        var database = new MotionDatabase();
        using (var archive = OpenFarc(game, game.Find("rob/mot_db.farc")))
        using (var stream = archive.Open("mot_db.bin", EntryStreamMode.MemoryStream)) database.Load(stream, true);
        var ids = new Dictionary<string, int>(StringComparer.OrdinalIgnoreCase);
        var motions = ReadCommonMotions(game, database, ids);
        string[] names = { "CMN_HAND_NORMAL", "CMN_HAND_OPEN", "CMN_HAND_HOLD", "CMN_HAND_FLASHLIGHT",
            "MIK_FACE_CLOSE", "MIK_KUCHI_A", "MIK_FACE_STRONG" };
        using (var writer = new BinaryWriter(File.Create(DivaImport.OutputPath(output, "gameplay.bin"))))
            foreach (string name in names) WriteMotion(writer, motions[ids[name]], skeleton, database, true);
    }

    private static int[] ReadCommonTable(GameArchives game)
    {
        using (var archive = OpenFarc(game, game.Find("rob/rob_mot_tbl.bin")))
        using (var stream = archive.Open("rob_cmn_mottbl.bin", EntryStreamMode.MemoryStream))
        using (var reader = new BinaryReader(stream))
        {
            int characters = reader.ReadInt32(), entries = reader.ReadInt32(), offset = reader.ReadInt32();
            if (characters <= 0 || entries <= 0 || entries > 4096) throw new InvalidDataException("Invalid common motion table.");
            stream.Position = offset; // MIK, default stance.
            int stances = reader.ReadInt32(), count = reader.ReadInt32();
            if (count <= 0) throw new InvalidDataException("No default Miku motion table.");
            stream.Position = stances;
            stream.Position = reader.ReadInt32();
            return Enumerable.Range(0, entries).Select(i => reader.ReadInt32()).ToArray();
        }
    }

    private static FarcArchive OpenFarc(GameArchives game, string path)
    {
        var archive = new FarcArchive();
        try { archive.Load(game.Open(path), false); return archive; }
        catch { archive.Dispose(); throw; }
    }

    private static void WriteString(BinaryWriter writer, string value)
    {
        var bytes = Encoding.UTF8.GetBytes(value);
        writer.Write(bytes.Length); writer.Write(bytes);
    }

    private static void WriteEvents(BinaryWriter writer, List<Event> events, bool motion)
    {
        writer.Write(events.Count);
        foreach (var entry in events.OrderBy(e => e.time))
        {
            writer.Write(entry.time); writer.Write(entry.start); writer.Write(entry.index);
            if (motion) { writer.Write(entry.speed); writer.Write(entry.blend); }
        }
    }

    private static void WriteVector(BinaryWriter writer, KeyBinding vector)
    {
        foreach (var axis in new[] { vector == null ? null : vector.X, vector == null ? null : vector.Y, vector == null ? null : vector.Z })
        {
            int count = axis == null ? 0 : axis.Keys.Count;
            writer.Write(axis != null && axis.HasTangents ? 3 : 2); writer.Write(count);
            if (count == 0) continue;
            foreach (var key in axis.Keys)
            {
                writer.Write((float)key.Frame); writer.Write(key.Value);
                writer.Write(key.Tangent); writer.Write(key.Tangent);
            }
        }
    }

    private static float Number(string value)
    {
        float result;
        if (!float.TryParse(value, NumberStyles.Float, CultureInfo.InvariantCulture, out result))
            throw new FormatException("invalid numeric value '" + value + "'");
        return result;
    }

    private static void WriteShot(BinaryWriter writer, int shot, Stream stream)
    {
        var magic = new byte[16];
        stream.Read(magic, 0, magic.Length); stream.Position = 0;
        if (!Encoding.ASCII.GetString(magic).StartsWith("#A3DA_", StringComparison.Ordinal))
            throw new NotSupportedException("Camera shot uses an unsupported A3DA format.");
        var fields = GameArchives.ReadProperties(stream);
        const string root = "camera_root.0.";
        if (!fields.ContainsKey(root + "view_point.fov.type"))
            throw new NotSupportedException("Camera shot has no supported camera root.");
        string fps = Get(fields, "play_control.fps"), begin = Get(fields, "play_control.begin");
        writer.Write(shot); writer.Write(fps.Length == 0 ? 60f : Number(fps));
        writer.Write(begin.Length == 0 ? 0f : Number(begin));
        writer.Write(Get(fields, root + "view_point.fov_is_horizontal") == "1");
        foreach (string path in new[] { "view_point.trans.x", "view_point.trans.y", "view_point.trans.z",
            "interest.trans.x", "interest.trans.y", "interest.trans.z", "view_point.roll", "view_point.fov" })
            WriteCameraCurve(writer, fields, root + path + ".");
    }

    private static void WriteCameraCurve(BinaryWriter writer, Dictionary<string, string> fields, string path)
    {
        string kind = Get(fields, path + "type");
        int type;
        if (!Int32.TryParse(kind, NumberStyles.Integer, CultureInfo.InvariantCulture, out type))
            throw new FormatException("missing curve type " + path);
        writer.Write(type);
        if (type == 0) { writer.Write(0); return; }
        if (type == 1)
        {
            writer.Write(1); writer.Write(0f); writer.Write(Number(Get(fields, path + "value")));
            writer.Write(0f); writer.Write(0f);
            return;
        }
        if (type < 2 || type > 4) throw new NotSupportedException("Unsupported A3DA curve type " + type);
        int count;
        if (!Int32.TryParse(Get(fields, path + "key.length"), NumberStyles.Integer, CultureInfo.InvariantCulture, out count))
            throw new FormatException("missing key count " + path);
        writer.Write(count);
        for (int i = 0; i < count; i++)
        {
            var key = Get(fields, path + "key." + i + ".data").Trim('(', ')').Split(',').Select(Number).ToArray();
            if (key.Length > 4) throw new InvalidDataException("Invalid A3DA key " + path + i);
            float incoming = key.Length > 2 ? key[2] : 0;
            writer.Write(key[0]); writer.Write(key.Length > 1 ? key[1] : 0); writer.Write(incoming);
            writer.Write(key.Length > 3 ? key[3] : incoming);
        }
    }
}
