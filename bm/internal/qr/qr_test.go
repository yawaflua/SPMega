package qr

import (
	"errors"
	"testing"

	"github.com/makiuchi-d/gozxing"
	"github.com/makiuchi-d/gozxing/qrcode"
)

func TestDecodeRGBA8(t *testing.T) {
	t.Parallel()

	const value = "https://spworlds.ru/pay/12345"
	capture := qrCapture(t, value, false)
	got, err := Decode(capture)
	if err != nil {
		t.Fatal(err)
	}
	if got != value {
		t.Fatalf("got %q, want %q", got, value)
	}
}

func TestDecodeInvertedRGBA8(t *testing.T) {
	t.Parallel()

	const value = "https://spworlds.ru/pay/54321"
	capture := qrCapture(t, value, true)
	got, err := Decode(capture)
	if err != nil {
		t.Fatal(err)
	}
	if got != value {
		t.Fatalf("got %q, want %q", got, value)
	}
}

func TestDecodeRejectsMalformedCapture(t *testing.T) {
	t.Parallel()

	tests := []RGBA8Image{
		{},
		{Width: 2, Height: 2, Stride: 7, Pixels: make([]byte, 14)},
		{Width: 2, Height: 2, Stride: 8, Pixels: make([]byte, 15)},
	}
	for _, capture := range tests {
		if _, err := Decode(capture); !errors.Is(err, ErrMalformedCapture) {
			t.Fatalf("Decode(%+v) error = %v, want ErrMalformedCapture", capture, err)
		}
	}
}

func TestDecodeBlankCapture(t *testing.T) {
	t.Parallel()

	pixels := make([]byte, 64*64*4)
	for i := range pixels {
		pixels[i] = 255
	}
	if _, err := DecodeRGBA8(64, 64, pixels); !errors.Is(err, ErrNotFound) {
		t.Fatalf("error = %v, want ErrNotFound", err)
	}
}

func qrCapture(t *testing.T, value string, inverted bool) RGBA8Image {
	t.Helper()
	const size = 160
	matrix, err := qrcode.NewQRCodeWriter().Encode(value, gozxing.BarcodeFormat_QR_CODE, size, size, nil)
	if err != nil {
		t.Fatal(err)
	}
	pixels := make([]byte, size*size*4)
	for y := 0; y < size; y++ {
		for x := 0; x < size; x++ {
			black := matrix.Get(x, y)
			if inverted {
				black = !black
			}
			shade := byte(255)
			if black {
				shade = 0
			}
			offset := (y*size + x) * 4
			pixels[offset], pixels[offset+1], pixels[offset+2], pixels[offset+3] = shade, shade, shade, 255
		}
	}
	return RGBA8Image{Width: size, Height: size, Stride: size * 4, Pixels: pixels}
}
