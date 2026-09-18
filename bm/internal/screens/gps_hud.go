package screens

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"math"
	"net/http"
	"sync"
	"time"

	"github.com/yawaflua/GoMinecraftBridge/sdk"
	"github.com/yawaflua/GoMinecraftBridge/sdk/client"
)

type City struct {
	Name             string
	NetherX, NetherZ int
}
type LaneInfo struct {
	Name          string
	Color         uint32
	Coord, Offset int64
}

// GPSHUD ports the lane/city logic from GpsHudRenderer. UpdatePosition feeds
// client coordinates supplied by GoMinecraftBridge.
type GPSHUD struct {
	mu          sync.RWMutex
	enabled     bool
	anchor      sdk.HUDAnchor
	dimension   string
	x, z        float64
	hasPosition bool
	cities      []City
	http        *http.Client
}

func NewGPSHUD(anchor sdk.HUDAnchor) *GPSHUD {
	return &GPSHUD{enabled: true, anchor: anchor, http: &http.Client{Timeout: 15 * time.Second}}
}
func (hud *GPSHUD) SetEnabled(enabled bool) { hud.mu.Lock(); hud.enabled = enabled; hud.mu.Unlock() }
func (hud *GPSHUD) Toggle()                 { hud.mu.Lock(); hud.enabled = !hud.enabled; hud.mu.Unlock() }
func (hud *GPSHUD) Enabled() bool           { hud.mu.RLock(); defer hud.mu.RUnlock(); return hud.enabled }
func (hud *GPSHUD) SetAnchor(anchor sdk.HUDAnchor) {
	hud.mu.Lock()
	hud.anchor = anchor
	hud.mu.Unlock()
}
func (hud *GPSHUD) UpdatePosition(dimension string, x, z float64) {
	hud.mu.Lock()
	hud.dimension, hud.x, hud.z, hud.hasPosition = dimension, x, z, true
	hud.mu.Unlock()
}
func (hud *GPSHUD) ClearPosition() { hud.mu.Lock(); hud.hasPosition = false; hud.mu.Unlock() }

func (hud *GPSHUD) FetchCities(ctx context.Context) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, "https://map.sp-mini.ru/api/map/territories", nil)
	if err != nil {
		return err
	}
	response, err := hud.http.Do(req)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return fmt.Errorf("city endpoint returned %d", response.StatusCode)
	}
	var wrappers []struct {
		Territory *struct {
			Name         string `json:"name"`
			NetherPortal []int  `json:"nether_portal"`
		} `json:"territory"`
	}
	decoder := json.NewDecoder(io.LimitReader(response.Body, 1<<20))
	if err := decoder.Decode(&wrappers); err != nil {
		return err
	}
	cities := make([]City, 0, len(wrappers))
	for _, wrapper := range wrappers {
		if wrapper.Territory != nil && len(wrapper.Territory.NetherPortal) >= 2 {
			cities = append(cities, City{Name: wrapper.Territory.Name, NetherX: wrapper.Territory.NetherPortal[0], NetherZ: wrapper.Territory.NetherPortal[1]})
		}
	}
	if len(cities) == 0 {
		return fmt.Errorf("city endpoint returned no usable territories")
	}
	hud.mu.Lock()
	hud.cities = cities
	hud.mu.Unlock()
	return nil
}

func (hud *GPSHUD) Render(context *client.Context) {
	hud.mu.RLock()
	enabled, anchor, dimension, x, z, hasPosition, cities := hud.enabled, hud.anchor, hud.dimension, hud.x, hud.z, hud.hasPosition, append([]City(nil), hud.cities...)
	hud.mu.RUnlock()
	if !enabled || !hasPosition || !isNether(dimension) {
		context.RemoveHUD("spmega-gps-bg")
		context.RemoveHUD("spmega-gps-bar")
		context.RemoveHUD("spmega-gps-title")
		context.RemoveHUD("spmega-gps-offset")
		context.RemoveHUD("spmega-gps-city")
		return
	}
	lane := CalculateLane(math.Round(x), math.Round(z))
	offset := formatOffset(lane.Offset)
	cityText := ""
	minDistance := math.MaxFloat64
	for _, city := range cities {
		cx, cz := float64(city.NetherX)/8, float64(city.NetherZ)/8
		distance := (x-cx)*(x-cx) + (z-cz)*(z-cz)
		if distance < minDistance {
			minDistance = distance
			cityLane := CalculateLane(math.Round(cx), math.Round(cz))
			cityText = fmt.Sprintf("Ближайший: %s ~(%s %d, см: %s)", city.Name, cityLane.Name, cityLane.Coord, formatOffset(cityLane.Offset))
		}
	}
	width := 230
	if runes := len([]rune(cityText))*6 + 18; runes > width {
		width = runes
	}
	height := 42
	if cityText != "" {
		height = 54
	}
	context.RenderHUD(sdk.HUDRectangle(0, 0, width, height, 0x90101010, anchor).Named("spmega-gps-bg"))
	context.RenderHUD(sdk.HUDRectangle(6, 6, 3, height-12, lane.Color, anchor).Named("spmega-gps-bar"))
	context.RenderHUD(sdk.HUDText(fmt.Sprintf("Ветка: %s (%d)", lane.Name, lane.Coord), 13, 6, 0xffffffff, true, anchor).Named("spmega-gps-title"))
	context.RenderHUD(sdk.HUDText("Смещение: "+offset, 13, 18, 0xffffffff, true, anchor).Named("spmega-gps-offset"))
	if cityText != "" {
		context.RenderHUD(sdk.HUDText(cityText, 13, 30, 0xffffffff, true, anchor).Named("spmega-gps-city"))
	}
}

func CalculateLane(x, z float64) LaneInfo {
	xi, zi := int64(math.Round(x)), int64(math.Round(z))
	absX, absZ := abs64(xi), abs64(zi)
	if absX > absZ {
		if xi > 0 {
			return LaneInfo{Name: "Зеленая", Color: 0xff55ff55, Coord: xi, Offset: zi}
		}
		return LaneInfo{Name: "Синяя", Color: 0xff5555ff, Coord: absX, Offset: zi}
	}
	if zi < 0 {
		return LaneInfo{Name: "Красная", Color: 0xffff5555, Coord: absZ, Offset: xi}
	}
	return LaneInfo{Name: "Желтая", Color: 0xffffff55, Coord: absZ, Offset: xi}
}
func abs64(value int64) int64 {
	if value < 0 {
		return -value
	}
	return value
}
func formatOffset(value int64) string {
	if value > 0 {
		return fmt.Sprintf("+%d", value)
	}
	return strconvFormatInt(value)
}
func strconvFormatInt(value int64) string { return fmt.Sprintf("%d", value) }
func isNether(dimension string) bool {
	return dimension == "minecraft:the_nether" || dimension == "the_nether" || dimension == "nether"
}
