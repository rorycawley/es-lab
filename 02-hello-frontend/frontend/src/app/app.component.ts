import { Component, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { toSignal, toObservable } from '@angular/core/rxjs-interop';
import { map, catchError, of, switchMap, startWith } from 'rxjs';
import { form, FormField } from '@angular/forms/signals';

interface HealthResponse {
  status: string;
  version: string;
}

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [FormField],
  templateUrl: './app.component.html',
})
export class AppComponent {
  private readonly http = inject(HttpClient);

  // Signal Form: model is the source of truth; form() wraps it for [formField] binding
  private readonly endpointModel = signal({ path: '/api/health' });
  readonly endpointForm = form(this.endpointModel);

  // Tuple [path, n] so check() always triggers a new request even when the path hasn't changed
  private readonly checkTrigger = signal<[string, number]>(['/api/health', 0]);

  readonly healthy = toSignal(
    toObservable(this.checkTrigger).pipe(
      switchMap(([path]) =>
        this.http.get<HealthResponse>(path).pipe(
          map(({ status }) => status === 'ok'),
          catchError(() => of(false)),
          startWith<boolean | null>(null),
        ),
      ),
    ),
    { initialValue: null as boolean | null },
  );

  check(): void {
    this.checkTrigger.update(([_, n]) => [this.endpointModel().path, n + 1]);
  }
}
