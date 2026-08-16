import { Component, computed, inject, InjectionToken, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { toSignal, toObservable } from '@angular/core/rxjs-interop';
import { catchError, combineLatest, debounce, distinctUntilChanged, map, of, startWith, switchMap, timer } from 'rxjs';
import { form, FormField } from '@angular/forms/signals';

interface ServiceRequest {
  request_id: string;
  submitted_by: string;
  title: string;
  description: string;
  status: string;
  created_at: string;
  updated_at: string;
}

export const SEARCH_DEBOUNCE_MS = new InjectionToken<number>('SEARCH_DEBOUNCE_MS', {
  providedIn: 'root',
  factory: () => 300,
});

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [FormField],
  templateUrl: './app.component.html',
})
export class AppComponent {
  private readonly http = inject(HttpClient);
  private readonly searchDebounceMs = inject(SEARCH_DEBOUNCE_MS);

  // Signal Forms: model is source of truth; form() wraps it for [formField] binding
  private readonly formModel = signal({ title: '', description: '' });
  readonly submitForm = form(this.formModel);

  // Integer trigger so each submit always fires a reload, even redundant ones
  private readonly loadTrigger = signal(0);
  readonly searchQuery = signal('');
  readonly isSearching = computed(() => this.searchQuery().trim().length > 0);

  readonly listError = signal(false);

  readonly requests = toSignal(
    combineLatest([
      toObservable(this.loadTrigger),
      toObservable(this.searchQuery).pipe(
        map((q) => q.trim()),
        distinctUntilChanged(),
        debounce((q) => (q && this.searchDebounceMs > 0 ? timer(this.searchDebounceMs) : of(undefined))),
      ),
    ]).pipe(
      switchMap(([, query]) => {
        this.listError.set(false);
        const request$ = query
          ? this.http.post<{ requests: ServiceRequest[] }>('/api/queries/search-service-requests', { query })
          : this.http.post<{ requests: ServiceRequest[] }>('/api/queries/list-service-requests', {});

        return request$.pipe(
          map((r) => r.requests),
          catchError(() => {
            this.listError.set(true);
            return of([] as ServiceRequest[]);
          }),
          startWith<ServiceRequest[] | null>(null),
        );
      }),
    ),
    { initialValue: null as ServiceRequest[] | null },
  );

  readonly submitState = signal<'idle' | 'submitting' | 'success' | 'error'>('idle');

  setSearchQuery(query: string): void {
    this.searchQuery.set(query);
  }

  submit(): void {
    const { title, description } = this.formModel();
    if (!title.trim() || !description.trim()) return;

    this.submitState.set('submitting');
    this.http
      .post('/api/commands/submit-service-request', { title: title.trim(), description: description.trim() })
      .subscribe({
        next: () => {
          this.submitState.set('success');
          this.formModel.set({ title: '', description: '' });
          if (this.isSearching()) {
            this.searchQuery.set('');
          } else {
            this.loadTrigger.update((n) => n + 1);
          }
          setTimeout(() => {
            if (this.submitState() === 'success') this.submitState.set('idle');
          }, 3000);
        },
        error: () => this.submitState.set('error'),
      });
  }
}
