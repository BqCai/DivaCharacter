require "ISUI/ISPanel"
require "ISUI/ISButton"
require "ISUI/ISComboBox"
require "ISUI/ISTextEntryBox"

DivaCharacterMenu = ISPanel:derive("DivaCharacterMenu")
local pendingChoice
local selectedPv

local function fitText(value, width)
    local font = UIFont.Small
    if getTextManager():MeasureStringX(font, value) <= width then return value end
    -- Keep the full error in the button tooltip. A byte slice could split a Japanese glyph.
    return "See the button tooltip for details."
end

function DivaCharacterMenu:prerender()
    ISPanel.prerender(self)
    self:drawText("DIVA Character", 18, 12, 0.4, 0.95, 0.85, 1, UIFont.Medium)
    self:drawText("MEGA39's+ folder or DivaMegaMix.exe", 18, 47, 0.85, 0.85, 0.85, 1, UIFont.Small)
    self:drawText("Hatsune Miku / Future Tone", 18, 122, 0.85, 0.85, 0.85, 1, UIFont.Small)
    local status = DivaCharacterAPI and DivaCharacterAPI.getStatus() or "ZombieBuddy API is not available"
    local catalog = DivaCharacterAPI and DivaCharacterAPI.getCatalogStatus() or ""
    self:drawText(fitText(catalog, self.width - 36), 18, 100, 0.75, 0.85, 0.85, 1, UIFont.Small)
    self:drawText(fitText(status, self.width - 36), 18, 182, 0.85, 0.85, 0.85, 1, UIFont.Small)
    self:drawText("PV preview  /  Viewpoint third person", 18, 257, 0.85, 0.85, 0.85, 1, UIFont.Small)
    self:drawText("Special expression preview  /  outside PV", 18, 388, 0.85, 0.85, 0.85, 1, UIFont.Small)
    self:drawText("Face soft fill", 18, 456, 0.85, 0.85, 0.85, 1, UIFont.Small)
    local pvStatus = DivaCharacterAPI and DivaCharacterAPI.getPvStatus() or ""
    local pvRow = self.pvs:getOptionData(self.pvs:getSelected())
    self:drawText(fitText(pvStatus, self.width - 36), 18, 316, 0.85, 0.85, 0.85, 1, UIFont.Small)
    self.play:setTooltip(pvRow and not pvRow.available and "This entry needs a supported solo Miku 3D PV." or pvStatus)
    self.play:setEnable(DivaCharacterAPI ~= nil and pvRow ~= nil and pvRow.available and not isClient() and not isServer())
    self.stop:setEnable(DivaCharacterAPI ~= nil and DivaCharacterAPI.isPvBusy())
    self.apply:setTooltip(status)
    self.scan:setTooltip(catalog)
    self.apply:setEnable(DivaCharacterAPI ~= nil and not DivaCharacterAPI.isPreparingModule())
    if DivaCharacterAPI then
        local path = DivaCharacterAPI.getGamePath()
        if path ~= self.indexedPath then self.path:setText(path); self.indexedPath = path end
        local version = path .. "|" .. tostring(DivaCharacterAPI.getModuleCount()) .. "|" .. tostring(DivaCharacterAPI.getPvCount()) .. "|" .. catalog
        if self.catalogVersion ~= version then
            self.catalogVersion = version
            self:refreshOutfits()
            self:refreshPvs()
        end
    end
end

function DivaCharacterMenu:createChildren()
    ISPanel.createChildren(self)
    self.indexedPath = DivaCharacterAPI and DivaCharacterAPI.getGamePath() or ""
    self.path = ISTextEntryBox:new(self.indexedPath, 18, 67, self.width - 232, 25)
    self.path:initialise()
    self:addChild(self.path)
    self.outfits = ISComboBox:new(18, 145, self.width - 36, 27)
    self.outfits.divaCatalogFont = true
    self.outfits:initialise()
    self.outfits:setEditable(true)
    self:addChild(self.outfits)
    self:refreshOutfits()
    self.pvs = ISComboBox:new(18, 281, self.width - 36, 27)
    self.pvs.divaCatalogFont = true
    self.pvs:initialise()
    self.pvs:setEditable(true)
    self:addChild(self.pvs)
    self:refreshPvs()
    local function add(label, x, y, width, action)
        local button = ISButton:new(x, y, width, 28, label, self, action)
        button:initialise(); self:addChild(button)
        return button
    end
    add("Browse", self.width - 204, 65, 80, function()
        if DivaCharacterAPI then DivaCharacterAPI.browseGamePath() end
    end)
    self.scan = add("Scan", self.width - 114, 65, 96, function()
        if DivaCharacterAPI then DivaCharacterAPI.setGamePath(self.path:getText()) end
    end)
    self.apply = add("Apply outfit", 18, 212, 142, function()
        local id = self.outfits:getOptionData(self.outfits:getSelected())
        local player = getPlayer()
        if DivaCharacterAPI and id and player then
            DivaCharacterAPI.applyModule(id)
            pendingChoice = { player = player, id = id }
        end
    end)
    add("Restore player", 170, 212, 142, function()
        if DivaCharacterAPI then
            pendingChoice = nil
            DivaCharacterAPI.restore()
            local player = getPlayer()
            if player then player:getModData().DivaCharacter = nil end
        end
    end)
    self.play = add("Play PV", 18, 342, 142, function()
        local row = self.pvs:getOptionData(self.pvs:getSelected())
        if DivaCharacterAPI and row and row.available and not isClient() and not isServer() then
            selectedPv = row.id
            DivaCharacterAPI.playPv(row.id)
            if DivaCharacterAPI.isPvBusy() then self:setVisible(false); self:removeFromUIManager() end
        end
    end)
    self.stop = add("Stop PV", 170, 342, 142, function()
        if DivaCharacterAPI then DivaCharacterAPI.stopPv() end
    end)
    add("Close", self.width - 114, 342, 96, function() self:setVisible(false); self:removeFromUIManager() end)
    self.expression = ISComboBox:new(18, 412, self.width - 150, 27)
    self.expression:initialise()
    self:addChild(self.expression)
    for i, name in ipairs({ "Auto (gameplay)", "Crying", "Crying / shocked", "Blank eyes / spirals",
        "Blank eyes / tears", "Line eyes", "Line eyes / shocked", "Classic crying", "Dizzy" }) do
        self.expression:addOptionWithData(name, i - 1)
    end
    if DivaCharacterAPI then self.expression:setSelected(DivaCharacterAPI.getHeadPreview() + 1) end
    add("Preview", self.width - 114, 412, 96, function()
        if DivaCharacterAPI then
            DivaCharacterAPI.setHeadPreview(self.expression:getOptionData(self.expression:getSelected()) or 0)
            self:setVisible(false); self:removeFromUIManager()
        end
    end)
    self.faceFill = ISComboBox:new(18, 480, self.width - 36, 27, self, function(panel, combo)
        if not DivaCharacterAPI or not DivaCharacterAPI.setFaceSoftFill then return end
        DivaCharacterAPI.setFaceSoftFill(combo:getOptionData(combo:getSelected()) or 0)
        local player = getPlayer()
        if player then player:getModData().DivaCharacterFaceFill = DivaCharacterAPI.getFaceSoftFill() end
    end)
    self.faceFill:initialise()
    self:addChild(self.faceFill)
    local currentFill = DivaCharacterAPI and DivaCharacterAPI.getFaceSoftFill and DivaCharacterAPI.getFaceSoftFill() or 100
    local foundFill = false
    for _, percent in ipairs({ 0, 50, 100, 150, 200 }) do
        local label = percent == 0 and "Off" or tostring(percent) .. "%"
        if percent == 100 then label = label .. " (default)" end
        self.faceFill:addOptionWithData(label, percent)
        if percent == currentFill then foundFill = true end
    end
    if not foundFill then self.faceFill:addOptionWithData(tostring(currentFill) .. "%", currentFill) end
    self.faceFill:setSelectedData(currentFill)
    self.faceFill:setEnabled(DivaCharacterAPI ~= nil and DivaCharacterAPI.setFaceSoftFill ~= nil)
    self.faceFill:setToolTipMap({ defaultTooltip = "Applies immediately to facial diffuse lighting. 100% is the weak default. Saved with this character." })
end

function DivaCharacterMenu:refreshPvs()
    local previous = self.pvs:getOptionData(self.pvs:getSelected())
    local wanted = previous and previous.id or selectedPv
    local selected = 1
    self.pvs:clear()
    if DivaCharacterAPI then
        for index = 0, DivaCharacterAPI.getPvCount() - 1 do
            local id = DivaCharacterAPI.getPvId(index)
            local name = DivaCharacterAPI.getPvName(index)
            local available = DivaCharacterAPI.getPvAvailable(index)
            local label = name .. " [PV" .. id .. "]" .. (available and "" or " (unavailable)")
            self.pvs:addOptionWithData(label, { id = id, available = available })
            if id == wanted then selected = index + 1 end
        end
    end
    self.pvs:setSelected(selected)
end

function DivaCharacterMenu:refreshOutfits()
    local previous = self.outfits:getOptionData(self.outfits:getSelected())
    local active = DivaCharacterAPI and DivaCharacterAPI.getActiveModule() or ""
    self.outfits:clear()
    if DivaCharacterAPI then
        for index = 0, DivaCharacterAPI.getModuleCount() - 1 do
            local id = DivaCharacterAPI.getModuleId(index)
            local name = DivaCharacterAPI.getModuleName(index)
            if id == "0" then name = "Default / " .. name end
            if id == "3" then name = "MEIKO Style / " .. name end
            if id ~= "" then self.outfits:addOptionWithData(name .. " [" .. id .. "]", id) end
        end
    end
    if not DivaCharacterAPI or not DivaCharacterAPI.hasBundledOutfit or DivaCharacterAPI.hasBundledOutfit() then
        self.outfits:addOptionWithData("Default Miku (bundled)", "miku_default")
    end
    self.outfits:setSelected(1)
    self.outfits:setSelectedData(active ~= "" and active or previous)
end

local function openMenu()
    if DivaCharacterMenu.instance then DivaCharacterMenu.instance:removeFromUIManager() end
    local width = math.min(650, getCore():getScreenWidth() - 32)
    local height = 525
    local menu = DivaCharacterMenu:new((getCore():getScreenWidth()-width)/2, (getCore():getScreenHeight()-height)/2, width, height)
    menu.divaCatalogFont = true
    menu:initialise(); menu:addToUIManager(); DivaCharacterMenu.instance = menu
end

Events.OnKeyPressed.Add(function(key) if key == Keyboard.KEY_F8 and getPlayer() then openMenu() end end)
Events.OnFillWorldObjectContextMenu.Add(function(player, context)
    context:addOption("DIVA Character...", nil, openMenu)
end)
Events.OnCreatePlayer.Add(function(index, player)
    local saved = player:getModData()
    if index == 0 and DivaCharacterAPI and DivaCharacterAPI.setFaceSoftFill then
        local percent = saved.DivaCharacterFaceFill
        if type(percent) ~= "number" or percent < 0 or percent > 200 then percent = 100 end
        DivaCharacterAPI.setFaceSoftFill(percent)
    end
    local id = saved.DivaCharacter
    if index == 0 and type(id) == "string" and DivaCharacterAPI then DivaCharacterAPI.applyModule(id) end
end)
Events.OnRenderTick.Add(function()
    if DivaCharacterAPI then DivaCharacterAPI.tickPv() end
    if not pendingChoice or not DivaCharacterAPI or DivaCharacterAPI.isPreparingModule() then return end
    if getPlayer() == pendingChoice.player and DivaCharacterAPI.getActiveModule() == pendingChoice.id then
        pendingChoice.player:getModData().DivaCharacter = pendingChoice.id
    end
    pendingChoice = nil
end)
Events.OnMainMenuEnter.Add(function()
    pendingChoice = nil
    if DivaCharacterAPI then DivaCharacterAPI.restore() end
end)
