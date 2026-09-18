package screens

import (
	"context"
	"sync"
	"time"
)

type Poller struct {
	cancel context.CancelFunc
	done   chan struct{}
	once   sync.Once
}

func StartPoller(parent context.Context, interval time.Duration, poll func(context.Context)) *Poller {
	ctx, cancel := context.WithCancel(parent)
	poller := &Poller{cancel: cancel, done: make(chan struct{})}
	go func() {
		defer close(poller.done)
		poll(ctx)
		ticker := time.NewTicker(interval)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				poll(ctx)
			}
		}
	}()
	return poller
}

func (poller *Poller) Stop() {
	if poller == nil {
		return
	}
	poller.once.Do(poller.cancel)
	<-poller.done
}
