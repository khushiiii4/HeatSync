import { useEffect, useRef } from 'react';
import { X } from 'lucide-react';

export const cx = (...a) => a.filter(Boolean).join(' ');

export function Skeleton({ className = '' }) {
  return <div className={cx('skeleton', className)} aria-hidden />;
}

const TONES = {
  neutral: 'border-ink-500 bg-ink-700/60 text-fg-soft',
  cyan: 'border-cyan/30 bg-cyan/10 text-cyan',
  ok: 'border-ok/30 bg-ok/10 text-ok',
  warn: 'border-warn/35 bg-warn/10 text-warn',
  orange: 'border-orange/40 bg-orange/10 text-orange',
  crit: 'border-crit/40 bg-crit/10 text-crit',
  blue: 'border-azure/40 bg-azure/10 text-sky',
};

export function Tag({ tone = 'neutral', children, className = '', title }) {
  return (
    <span title={title} className={cx('inline-flex items-center gap-1 rounded-md border px-2 py-0.5 text-xs font-semibold uppercase tracking-wide', TONES[tone], className)}>
      {children}
    </span>
  );
}

export const levelTone = (level) => ({ WATCH: 'warn', WARNING: 'orange', CRITICAL: 'crit', NORMAL: 'ok' })[level] || 'neutral';

export function Button({ variant = 'secondary', size = 'md', className = '', children, ...rest }) {
  const base =
    'inline-flex items-center justify-center gap-2 rounded-lg font-medium transition-colors duration-150 disabled:cursor-not-allowed disabled:opacity-45 select-none';
  const sizes = { sm: 'h-8 px-3 text-sm', md: 'h-10 px-4 text-[15px]', lg: 'h-11 px-5 text-base' };
  const variants = {
    primary: 'bg-gradient-to-b from-cyan to-azure text-ink-950 hover:brightness-110 font-semibold shadow-[0_0_0_1px_rgb(255_255_255/0.12)_inset]',
    secondary: 'border border-ink-500 bg-ink-700 text-fg hover:border-ink-500 hover:bg-ink-600',
    ghost: 'text-fg-soft hover:bg-ink-700 hover:text-fg',
    danger: 'border border-crit/50 bg-crit/10 text-crit hover:bg-crit/20',
    warn: 'border border-warn/45 bg-warn/10 text-warn hover:bg-warn/20',
  };
  return (
    <button type="button" className={cx(base, sizes[size], variants[variant], className)} {...rest}>
      {children}
    </button>
  );
}

export function EmptyState({ icon: Icon, title, body, children }) {
  return (
    <div className="flex flex-col items-center justify-center gap-2 px-6 py-10 text-center">
      {Icon && (
        <div className="mb-1 flex h-11 w-11 items-center justify-center rounded-xl border border-ink-500 bg-ink-700/70 text-fg-mute">
          <Icon className="h-5 w-5" aria-hidden />
        </div>
      )}
      <p className="text-base font-medium text-fg">{title}</p>
      {body && <p className="max-w-sm text-sm text-fg-mute">{body}</p>}
      {children}
    </div>
  );
}

/** Accessible modal: focus is moved in, Escape closes, focus returns on close. */
export function Modal({ open, onClose, title, children, footer, width = 'max-w-lg' }) {
  const ref = useRef(null);
  useEffect(() => {
    if (!open) return undefined;
    const previous = document.activeElement;
    ref.current?.focus();
    const onKey = (e) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => {
      window.removeEventListener('keydown', onKey);
      previous?.focus?.();
    };
  }, [open, onClose]);
  if (!open) return null;
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4" role="presentation">
      <div className="absolute inset-0 bg-ink-950/75 backdrop-blur-sm" onClick={onClose} aria-hidden />
      <div ref={ref} tabIndex={-1} role="dialog" aria-modal="true" aria-label={title} className={cx('rise card-surface relative w-full', width, 'max-h-[88vh] overflow-hidden outline-none')}>
        <div className="flex items-center justify-between border-b border-ink-600 px-5 py-4">
          <h2 className="text-lg font-semibold text-fg">{title}</h2>
          <button type="button" onClick={onClose} className="rounded-md p-1.5 text-fg-mute transition hover:bg-ink-600 hover:text-fg" aria-label="Close dialog">
            <X className="h-5 w-5" />
          </button>
        </div>
        <div className="max-h-[62vh] overflow-y-auto px-5 py-4">{children}</div>
        {footer && <div className="flex justify-end gap-2 border-t border-ink-600 bg-ink-900/40 px-5 py-3.5">{footer}</div>}
      </div>
    </div>
  );
}

export function ConfirmDialog({ open, title, body, confirmLabel = 'Confirm', tone = 'danger', busy, onConfirm, onCancel }) {
  return (
    <Modal
      open={open}
      onClose={onCancel}
      title={title}
      width="max-w-md"
      footer={
        <>
          <Button variant="ghost" onClick={onCancel}>
            Cancel
          </Button>
          <Button variant={tone === 'danger' ? 'danger' : 'primary'} onClick={onConfirm} disabled={busy}>
            {busy ? 'Working…' : confirmLabel}
          </Button>
        </>
      }
    >
      <div className="space-y-2 text-[15px] leading-relaxed text-fg-soft">{body}</div>
    </Modal>
  );
}

export function Segmented({ value, onChange, options, label }) {
  return (
    <div role="group" aria-label={label} className="inline-flex rounded-lg border border-ink-500 bg-ink-900/60 p-0.5">
      {options.map((o) => (
        <button
          key={o.value}
          type="button"
          aria-pressed={value === o.value}
          onClick={() => onChange(o.value)}
          className={cx('rounded-md px-3 py-1 text-sm font-medium transition-colors', value === o.value ? 'bg-ink-600 text-fg shadow-sm' : 'text-fg-mute hover:text-fg')}
        >
          {o.label}
        </button>
      ))}
    </div>
  );
}

export const inputCls =
  'h-10 w-full rounded-lg border border-ink-500 bg-ink-900/70 px-3 text-[15px] text-fg placeholder:text-fg-faint transition-colors hover:border-ink-500 focus:border-cyan focus:outline-none focus:ring-2 focus:ring-cyan/30';
