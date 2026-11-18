import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { Panel } from './Panel';

const source = (over = {}) => ({
  service: 'SCHEDULER-SERVICE',
  isLoading: false,
  hasData: true,
  isError: false,
  stale: false,
  down: false,
  frozen: false,
  lastServerTime: '2026-11-18T12:41:05.000Z',
  refetch: vi.fn(),
  ...over,
});

describe('Panel', () => {
  it('shows a skeleton on first load', () => {
    render(<Panel title="Decision feed" sources={[source({ isLoading: true, hasData: false })]}>content</Panel>);
    expect(screen.getByLabelText('Loading')).toBeInTheDocument();
    expect(screen.queryByText('content')).not.toBeInTheDocument();
  });

  it('shows live content with no badge when the source is healthy', () => {
    render(<Panel title="Decision feed" sources={[source()]}>content</Panel>);
    expect(screen.getByText('content')).toBeInTheDocument();
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });

  it('keeps the last data and shows a stale badge naming the service and the SERVER timestamp', () => {
    render(<Panel title="Decision feed" sources={[source({ stale: true, isError: true })]}>last good rows</Panel>);
    expect(screen.getByText('last good rows')).toBeInTheDocument();
    const badge = screen.getByRole('status');
    expect(badge).toHaveTextContent('Scheduler unavailable, last update');
    // the clock shown is the one carried in the data, formatted the same way the app formats it
    const shown = new Date('2026-11-18T12:41:05.000Z').toLocaleTimeString([], { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' });
    expect(badge).toHaveTextContent(shown);
  });

  it('shows an error with retry when a source has never delivered', () => {
    const refetch = vi.fn();
    render(<Panel title="Decision feed" sources={[source({ hasData: false, down: true, isError: true, lastServerTime: null, refetch })]}>content</Panel>);
    expect(screen.getByRole('alert')).toHaveTextContent('Scheduler unavailable');
    expect(screen.queryByText('content')).not.toBeInTheDocument();
  });

  it('with several sources, a partial outage keeps the working data and names only the failing service', () => {
    const feed = [source(), source({ service: 'COOLING-CONTROLLER-SERVICE', hasData: false, down: true, isError: true, lastServerTime: null })];
    render(<Panel title="Decision feed" sources={feed}>partial content</Panel>);
    expect(screen.getByText('partial content')).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('Cooling controller unavailable');
  });

  it('contains a render error inside the panel (independent error boundary)', () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {});
    const Boom = () => {
      throw new Error('boom');
    };
    render(
      <>
        <Panel title="Broken" sources={[source()]}><Boom /></Panel>
        <Panel title="Healthy" sources={[source()]}>still here</Panel>
      </>,
    );
    expect(screen.getByText('This panel could not be displayed')).toBeInTheDocument();
    expect(screen.getByText('still here')).toBeInTheDocument();
    spy.mockRestore();
  });

  it('flags data that is not advancing', () => {
    render(<Panel title="Heat map" sources={[source({ frozen: true, service: 'TELEMETRY-SERVICE' })]}>rows</Panel>);
    expect(screen.getByRole('status')).toHaveTextContent('Data not advancing');
  });
});
